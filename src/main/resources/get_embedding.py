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

#initialize models
tokenizer = AutoTokenizer.from_pretrained("BAAI/bge-small-en-v1.5")
model = AutoModel.from_pretrained("BAAI/bge-small-en-v1.5")
model.eval()

def get_embedding(input_string):
    with torch.no_grad():

        encoded_input = tokenizer([input_string], padding=True, truncation=True, return_tensors='pt')
        model_output = model(**encoded_input)
        # Perform pooling. In this case, cls pooling.
        embedding = model_output[0][:, 0]
        embedding = torch.nn.functional.normalize(embedding, p=2, dim=1)[0]
        return embedding.tolist()

gr.Interface(fn=get_embedding, inputs="text", outputs="text").launch(server_port=7860)