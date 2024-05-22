#
# Copyright 2021 the original author or authors.
# <p>
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# <p>
# https://www.apache.org/licenses/LICENSE-2.0
# <p>
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import os
os.environ["XDG_CACHE_HOME"]="/HF_CACHE"
os.environ["HF_HOME"]="/HF_CACHE/huggingface"
os.environ["HUGGINGFACE_HUB_CACHE"]="/HF_CACHE/huggingface/hub"
os.environ["TRANSFORMERS_CACHE"]="/HF_CACHE/huggingface"
import torch #pytorch = 2.0.1
from transformers import AutoModel, AutoTokenizer, logging # 4.29.2
import gradio as gr # 3.23.0
logging.set_verbosity_error()
from abc import ABC, abstractmethod
from enum import Enum
from FlagEmbedding import FlagReranker
import numpy as np
from transformers import AutoModelForMaskedLM, AutoTokenizer, AutoModel
from sentence_transformers import SentenceTransformer

class EncoderModel(ABC):
    @abstractmethod
    def encode(self, text: str) -> np.ndarray:
        """Encode the text into a sequence embedding."""


class SentenceTransformerModel(EncoderModel):
    def __init__(self, checkpoint: str, device: str):
        self.model = SentenceTransformer(checkpoint, device=device)

        # select the prompts that the model expects for queries and passages
        if checkpoint == "BAAI/bge-large-en-v1.5":
            # see https://huggingface.co/BAAI/bge-large-en-v1.5
            self.prompts = {
                True: "Represent this sentence for searching relevant passages: ",
                False: "",
            }
        else:
            self.prompts = {
                True: "",
                False: "",
            }

    def encode(self, text: str, is_query: bool = False) -> np.ndarray:
        return self.model.encode(self.prompts[is_query] + text, convert_to_numpy=True)



class Retriever(ABC):
    _sigmoid_shift: float
    _sigmoid_scale: float

    @abstractmethod
    def predict(self, query: str, snippet: str) -> float:
        """Returns a normalized score between [0, 1] reflecting the likelihood that the snippet is a
        positive match for the query."""

    def _scaled_sigmoid(self, a):
        """a scaled sigmoid function to map values to [0, 1]"""
        return 1 / (1 + np.exp(-self._sigmoid_scale * (a - self._sigmoid_shift)))

class Reranker(Retriever):
    """Embeds the query and snippet jointly to create a similarity score."""

    # map values between [-10, 2] onto a large region of [0, 1]
    _sigmoid_shift = -4
    _sigmoid_scale = 0.3

    def __init__(self, checkpoint: str):
        self.model = FlagReranker(checkpoint, use_fp16=False)

    def predict(self, query: str, snippet: str) -> float:
        score = self.model.compute_score([query, snippet])

        return self._scaled_sigmoid(score)


class StaticModel(Retriever):
    """Uses an embedding model to encode the query and snippet separately and compute a distance."""

    _cache: dict[tuple[str, bool], np.ndarray]

    # map values between [10, 25] onto a large region of [0, 1]
    _sigmoid_shift = 18.0
    _sigmoid_scale = 0.1

    def __init__(self, checkpoint: str):
        self.model = SentenceTransformerModel(checkpoint, device="cpu")
        self._cache = {}

    def _encode(self, s: str, is_query: bool = False) -> np.ndarray:
        """caching wrapper for self.model.encode"""
        if (s, is_query) in self._cache:
            return self._cache[(s, is_query)]
        v = self.model.encode(s, is_query)
        self._cache[(s, is_query)] = v

        return v

    def predict(self, query: str, snippet: str) -> float:
        q_v = self._encode(query, is_query=True)
        s_v = self._encode(snippet)
        dist = np.linalg.norm(s_v - q_v)
        return self._scaled_sigmoid(dist)

class HF(Retriever):
    """Uses an embedding model to encode the query and snippet separately and compute a distance."""

    _cache: dict[str, np.ndarray]

    # map values between [10, 25] onto a large region of [0, 1]
    _sigmoid_shift = 18.0
    _sigmoid_scale = 0.1

    def __init__(self, checkpoint: str):
        tokenizer = AutoTokenizer.from_pretrained(checkpoint)
        model = AutoModel.from_pretrained(checkpoint)
        model.eval()
        self.model = model
        self.tokenizer = tokenizer
        self._cache = {}

    def _encode(self, s: str) -> np.ndarray:
        """caching wrapper for self.model.encode"""
        if s in self._cache:
            return self._cache[s]
        encoded_input = self.tokenizer(s, padding=False, truncation=True, return_tensors='pt', max_length=512) #TODO: add truncation for mini model to 512
        with torch.no_grad():
            model_output = self.model(**encoded_input)
            # Perform pooling. In this case, cls pooling.
            sentence_embeddings = model_output[0][:, 0]
        # normalize embeddings
        v = sentence_embeddings / np.linalg.norm(sentence_embeddings, ord=2, axis=1, keepdims=True)
        self._cache[s] = v
        return v

    def predict(self, query: str, snippet: str) -> float:
        q_v = self._encode(query)
        s_v = self._encode(snippet)

        dist = np.linalg.norm(s_v - q_v)
        return dist


class ClassificationResult(Enum):
    YES = 1
    MAYBE = 0
    NO = -1

    def to_int(self):
        """Convert the enum member to int."""
        return self.value

class Classifier():
    def __init__(self, true_threshold: float, false_threshold: float, retriever: Retriever, lower_score_indicates_true: bool = False):
        self.true_threshold = true_threshold
        self.false_threshold = false_threshold
        self.retriever = retriever
        self.lower_score_indicates_true = lower_score_indicates_true

    def classify(self, query: str, snippet: str) -> ClassificationResult:
        """Predicts a classification of the snippet as a positive match for the query."""
        score = self.retriever.predict(query, snippet)
        if self.lower_score_indicates_true:
            if score >= self.false_threshold:
                return ClassificationResult.NO
            elif score <= self.true_threshold:
                return ClassificationResult.YES
            else:
                return ClassificationResult.MAYBE
        else:
            if score >= self.true_threshold:
                return ClassificationResult.YES
            elif score <= self.false_threshold:
                return ClassificationResult.NO
            else:
                return ClassificationResult.MAYBE

class ChainedClassifier():
    def __init__(self, list_of_classifiers: list[Classifier]):
        self.list_of_classifiers = list_of_classifiers

    def classify(self, query: str, snippet: str) -> ClassificationResult:
        for classifier in self.list_of_classifiers:
            result = classifier.classify(query, snippet)
            if result == ClassificationResult.NO:
                return ClassificationResult.NO
            if result == ClassificationResult.YES:
                return ClassificationResult.YES
            # all classifiers returned MAYBE
        return ClassificationResult.MAYBE

#initiliaze models
thresholds = {"HF": [1-0.3815, 1-0.1624], "Distance": [1-0.84894, 1-0.84572]}
distance_classifier = Classifier(thresholds["Distance"][0], thresholds["Distance"][1], StaticModel("BAAI/bge-large-en-v1.5"), lower_score_indicates_true=True)
mini_classifier = Classifier(thresholds["HF"][0], thresholds["HF"][1], HF("SmartComponents/bge-micro-v2"), lower_score_indicates_true=True)
chained_classifier = ChainedClassifier([mini_classifier, distance_classifier])
def get_is_related(query, input_string, new_thresholds=None):
    global thresholds, distance_classifier, mini_classifier, chained_classifier
    if new_thresholds:
        if new_thresholds["HF"] != thresholds["HF"]:
            thresholds["HF"] = new_thresholds["HF"]
            mini_classifier = Classifier(thresholds["HF"][0], thresholds["HF"][1], HF("SmartComponents/bge-micro-v2"), lower_score_indicates_true=True)
        if new_thresholds["Distance"] != thresholds["Distance"]:
            thresholds["Distance"] = new_thresholds["Distance"]
            distance_classifier = Classifier(thresholds["Distance"][0], thresholds["Distance"][1], StaticModel("BAAI/bge-large-en-v1.5"), lower_score_indicates_true=True)
        chained_classifier = ChainedClassifier([mini_classifier, distance_classifier])
    result = chained_classifier.classify(query, input_string)
    return result.to_int()

gr.Interface(fn=get_is_related, inputs=["text", "text", "json"], outputs="text").launch(server_port=7871)