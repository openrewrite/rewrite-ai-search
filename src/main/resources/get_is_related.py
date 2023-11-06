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
from os.path import join
os.environ["XDG_CACHE_HOME"]="/HF_CACHE"
os.environ["HF_HOME"]="/HF_CACHE/huggingface"
os.environ["HUGGINGFACE_HUB_CACHE"]="/HF_CACHE/huggingface/hub"
os.environ["TRANSFORMERS_CACHE"]="/HF_CACHE/huggingface"
# os.environ["XDG_CACHE_HOME"]=os.path.expanduser("~") + "/.hfcache/HF_CACHE"
# os.environ["HF_HOME"]=os.path.expanduser("~") + "/.hfcache/HF_CACHE/huggingface"
# os.environ["HUGGINGFACE_HUB_CACHE"]=os.path.expanduser("~") + "/.hfcache/HF_CACHE/huggingface/hub"
# os.environ["TRANSFORMERS_CACHE"]=os.path.expanduser("~") + "/.hfcache/HF_CACHE/huggingface"
import torch #pytorch = 2.0.1
from typing import List, Union, Dict
from transformers import AutoModel, AutoTokenizer, logging # 4.29.2
from abc import ABC, abstractmethod
import gradio as gr # 3.23.0
import huggingface_hub
import math

logging.set_verbosity_error()

#initialize models
tokenizer = AutoTokenizer.from_pretrained("BAAI/bge-small-en-v1.5")
model = AutoModel.from_pretrained("BAAI/bge-small-en-v1.5")
model.eval()
#GRADIO

def is_related(query, code, threshold=0.30):
    assert threshold>=0 and threshold<=1
    with torch.no_grad():
        encoded_input = tokenizer([query,code], padding=True, truncation=True, return_tensors='pt')
        model_output = model(**encoded_input)
        # Perform pooling. In this case, cls pooling.
        sentence_embeddings = model_output[0][:, 0]
    # normalize embeddings
    sentence_embeddings = torch.nn.functional.normalize(sentence_embeddings, p=2, dim=1)
    cosine_distance = 1-torch.nn.functional.cosine_similarity(sentence_embeddings[0], sentence_embeddings[1], dim=0)
    return 1 if cosine_distance<=threshold else 0


gr.Interface(fn=is_related, inputs=["text", "text", "number"], outputs="text").launch(server_port=7860)
