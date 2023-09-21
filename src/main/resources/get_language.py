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
# os.environ["XDG_CACHE_HOME"]=os.path.expanduser("~") + "/.hfcache/HF_CACHE"
# os.environ["HF_HOME"]=os.path.expanduser("~") + "/.hfcache/HF_CACHE/huggingface"
# os.environ["HUGGINGFACE_HUB_CACHE"]=os.path.expanduser("~") + "/.hfcache/HF_CACHE/huggingface/hub"
# os.environ["TRANSFORMERS_CACHE"]=os.path.expanduser("~") + "/.hfcache/HF_CACHE/huggingface"
from transformers import logging, pipeline # 4.29.2
import gradio as gr # 3.23.0
logging.set_verbosity_error()

pipe = pipeline("text-classification", model="papluca/xlm-roberta-base-language-detection")
tokenizer_kwargs = {'truncation':True, 'max_length':512}

def get_language(comment):
    lang = pipe(comment, **tokenizer_kwargs)[0]
    confidence = lang["score"]
    lang = lang["label"]
    if confidence <= 0.5:
        return "unknown"
    return lang

gr.Interface(fn=get_language, inputs=["text"], outputs="text").launch(server_port=7861)
