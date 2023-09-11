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
os.environ["XDG_CACHE_HOME"]=os.path.expanduser("~") + "/HF_CACHE"
os.environ["HF_HOME"]=os.path.expanduser("~") + "/HF_CACHE/huggingface"
os.environ["HUGGINGFACE_HUB_CACHE"]=os.path.expanduser("~") + "/HF_CACHE/huggingface/hub"
os.environ["TRANSFORMERS_CACHE"]=os.path.expanduser("~") + "/HF_CACHE/huggingface"

locations = []
lookfor = "HF_CACHE"
for root, dirs, files in os.walk('/'):
    if lookfor in dirs:
        locations.append(join(root, lookfor))

raise Exception("os path expanduser is " + os.path.expanduser("~")+"\nAnd we found HF_CACHE at "+ str(locations))
import torch #pytorch = 2.0.1
from typing import List, Union, Dict
from transformers import AutoModel, AutoTokenizer, logging # 4.29.2
from abc import ABC, abstractmethod
import gradio as gr # 3.23.0
import huggingface_hub
import math

#
# HUGGING_FACE_TOKEN = "" #don't need this anymore
# huggingface_hub.login(HUGGING_FACE_TOKEN) #don't need this anymore
logging.set_verbosity_error()

#encoder models
MAX_TOKEN_LEN = 1024
DEVICE = "cpu"
# Special tokens
MASK_TOKEN = "<mask>"
SEPARATOR_TOKEN = "<sep>"
PAD_TOKEN = "<pad>"
CLS_TOKEN = "<cls>"



def pool_and_normalize(
    features_sequence: torch.Tensor,
    attention_masks: torch.Tensor,
    return_norms: bool = False,
) -> Union[torch.Tensor, List[torch.Tensor]]:
    """Temporal ooling of sequences of vectors and projection onto the unit sphere.

    Args:
        features_sequence (torch.Tensor): Inpute features with shape [B, T, F].
        attention_masks (torch.Tensor): Pooling masks with shape [B, T, F].
        return_norms (bool, optional): Whether to additionally return the norms. Defaults to False.

    Returns:
        Union[torch.Tensor, List[torch.Tensor]]: Pooled and normalized vectors with shape [B, F].
    """

    pooled_embeddings = pooling(features_sequence, attention_masks)
    embedding_norms = pooled_embeddings.norm(dim=1)

    normalizing_factor = torch.where(  # Only normalize embeddings with norm > 1.0.
        embedding_norms > 1.0, embedding_norms, torch.ones_like(embedding_norms)
    )

    pooled_normalized_embeddings = pooled_embeddings / normalizing_factor[:, None]

    if return_norms:
        return pooled_normalized_embeddings, embedding_norms
    else:
        return pooled_normalized_embeddings

def prepare_tokenizer(tokenizer_path):
    
    tokenizer = AutoTokenizer.from_pretrained(tokenizer_path)

    tokenizer.add_special_tokens({"pad_token": PAD_TOKEN})
    tokenizer.add_special_tokens({"sep_token": SEPARATOR_TOKEN})
    tokenizer.add_special_tokens({"cls_token": CLS_TOKEN})
    tokenizer.add_special_tokens({"mask_token": MASK_TOKEN})
    return tokenizer

def pooling(x: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    """Pools a batch of vector sequences into a batch of vector global representations.
    It does so by taking the last vector in the sequence, as indicated by the mask.

    Args:
        x (torch.Tensor): Batch of vector sequences with shape [B, T, F].
        mask (torch.Tensor): Batch of masks with shape [B, T].

    Returns:
        torch.Tensor: Pooled version of the input batch with shape [B, F].
    """

    eos_idx = mask.sum(1) - 1
    batch_idx = torch.arange(len(eos_idx), device=x.device)

    mu = x[batch_idx, eos_idx, :]

    return mu

def set_device(inputs: Dict[str, torch.Tensor], device: str) -> Dict[str, torch.Tensor]:
    output_data = {}
    for k, v in inputs.items():
        output_data[k] = v.to(device)

    return output_data

class BaseEncoder(torch.nn.Module, ABC):

    def __init__(self, device, maximum_token_len, model_name):#, HF_token):
        super().__init__()

        self.model_name = model_name
        self.tokenizer = prepare_tokenizer(model_name)
        self.encoder = AutoModel.from_pretrained(model_name).to(device).eval()
        self.device = device
        self.maximum_token_len = maximum_token_len

    @abstractmethod
    def forward(self,):
        pass

    def encode(self, input_sentences, batch_size=32, **kwargs):

        # truncated_input_sentences = truncate_sentences(input_sentences, self.max_input_len)

        # n_batches = len(truncated_input_sentences) // batch_size + int(len(truncated_input_sentences) % batch_size > 0)

        embedding_batch_list = []

        # for i in range(n_batches):
        #     start_idx = i*batch_size
        #     end_idx = min((i+1)*batch_size, len(truncated_input_sentences))

        with torch.no_grad():
            embedding_batch_list.append(
                self.forward(input_sentences).detach().cpu()
            )

        input_sentences_embedding = torch.cat(embedding_batch_list)

        return [emb.squeeze().numpy() for emb in input_sentences_embedding]

class BigCodeEncoder(BaseEncoder):

    def __init__(self, device, maximum_token_len):
        super().__init__(device, maximum_token_len, model_name = "bigcode/starencoder")

    def forward(self, input_sentences):

        inputs = self.tokenizer(
            [f"{CLS_TOKEN}{sentence}{SEPARATOR_TOKEN}" for sentence in input_sentences],
            padding="longest",
            max_length=self.maximum_token_len,
            truncation=True,
            return_tensors="pt",
            )

        outputs = self.encoder(**set_device(inputs, self.device))
        embedding = pool_and_normalize(outputs.hidden_states[-1], inputs.attention_mask)

        return embedding


import torch.nn as nn


class CustomModel(nn.Module):
    def __init__(self):
          super(CustomModel, self).__init__()
          self.layer1 = nn.Linear(768*2, 768)
          self.act1 = nn.ReLU()
          self.dropout1 = nn.Dropout(0.1)
          self.layer2 = nn.Linear(768, 1)
          self.sigmoid = nn.Sigmoid()
    def forward(self, ids):
        x = self.act1(self.layer1(ids))
        x = self.dropout1(x)
        x = self.layer2(x)
        x = self.sigmoid(x).squeeze(dim=0)
        return x

#initialize models
# print(os.getenv())
bigcode_model = BigCodeEncoder("cpu", MAX_TOKEN_LEN)#embedding model
PATH = os.path.expanduser("~")+"/.moderne/models/torch_model"
#custom layer on top
model = CustomModel()
model.load_state_dict(torch.load(PATH))
model.eval()
#GRADIO

def is_related(query, code, threshold=0.5):
    assert threshold>=0 and threshold<=1
    with torch.no_grad(): #no need to keep track of the gradients
        q_emb =  torch.from_numpy(bigcode_model.encode([query])[0])
        c_emb =  torch.from_numpy(bigcode_model.encode([code])[0])
        X_input = torch.cat((q_emb, c_emb))
        y_pred = model(X_input)
        return math.floor(y_pred) if y_pred < threshold else math.ceil(y_pred)



def query_embedding(query):
    embedding = bigcode_model.encode([query])
    return str(list(embedding[0]))

gr.Interface(fn=is_related, inputs=["text", "text", "number"], outputs="text").launch()
