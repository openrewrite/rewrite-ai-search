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
from abc import ABC, abstractmethod
import numpy as np
from transformers import AutoModel, AutoTokenizer, logging # 4.29.2
import gradio as gr # 3.23.0
from sentence_transformers import SentenceTransformer
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


class SentenceTransformerModel(EncoderModel):
    def __init__(self, checkpoint: str, device: str):
        self.model = SentenceTransformer(checkpoint, device=device)

    def encode(self, text: str) -> np.ndarray:
        return self.model.encode(text, convert_to_numpy=True)


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
reranker = Reranker("BAAI/bge-reranker-large")

def get_is_related(query, codesnippet, threshold):
    return str(reranker.predict(query, codesnippet)>threshold)


gr.Interface(fn=get_is_related, inputs=["text","text", "number"], outputs="text").launch(server_port=7869)