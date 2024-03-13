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
from FlagEmbedding import FlagReranker
import os
os.environ["XDG_CACHE_HOME"]="/HF_CACHE"
os.environ["HF_HOME"]="/HF_CACHE/huggingface"
os.environ["HUGGINGFACE_HUB_CACHE"]="/HF_CACHE/huggingface/hub"
os.environ["TRANSFORMERS_CACHE"]="/HF_CACHE/huggingface"
from abc import ABC, abstractmethod
import numpy as np
from transformers import AutoModel, AutoTokenizer, logging # 4.29.2
import gradio as gr # 3.23.0
import torch
logging.set_verbosity_error()

class EncoderModel(ABC):
    @abstractmethod
    def encode(self, text: str) -> np.ndarray:
        """Encode the text into a sequence embedding."""

class Retriever(ABC):
    _sigmoid_shift: float
    _sigmoid_scale: float

    @abstractmethod
    def predict(self, query: str, snippet: str) -> float:
        """Returns a normalized score between [0, 1] reflecting the likelihood that the snippet is a
        positive match for the query."""

    def _scaled_sigmoid(self, a: np.number | np.ndarray) -> np.number | np.ndarray:
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
#initialize models

class HF(Retriever):
    """Uses an embedding model to encode the query and snippet separately and compute a distance."""

    _cache: dict[str, np.ndarray]



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
        encoded_input = self.tokenizer(s, padding=False, truncation=True, max_length=512, return_tensors='pt')

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

# model = Reranker("BAAI/bge-reranker-large") # threshold should model.predict(query, codesnippet)>threshold)
# since higher score means more likely to be related

model = HF("TaylorAI/bge-micro-v2")
smaller_means_closer = True

def get_is_related(query, codesnippet, threshold):

    if smaller_means_closer:
        return str(model.predict(query, codesnippet)<threshold)
    else:
        return str(model.predict(query, codesnippet)>threshold)


gr.Interface(fn=get_is_related, inputs=["text","text", "number"], outputs="text").launch(server_port=7869)