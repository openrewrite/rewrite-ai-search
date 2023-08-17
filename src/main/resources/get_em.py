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
import torch #pytorch = 2.0.1

os.environ["XDG_CACHE_HOME"]="/HF_CACHE"
os.environ["HF_HOME"]="/HF_CACHE/huggingface"
os.environ["HUGGINGFACE_HUB_CACHE"]="HF_CACHE/huggingface/hub"
os.environ["TRANSFORMERS_CACHE"]="/HF_CACHE/huggingface"

from typing import List, Union, Dict
from transformers import AutoModel, AutoTokenizer, logging # 4.29.2
from abc import ABC, abstractmethod
import gradio as gr # 3.23.0
import huggingface_hub

# HUGGING_FACE_TOKEN = os.environ.get('HUGGING_FACE_TOKEN') #don't need this anymore
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

bigcode_model = BigCodeEncoder("cpu", MAX_TOKEN_LEN)
#GRADIO
def query_embedding(query):

    embedding = bigcode_model.encode([query])
    return str(list(embedding[0]))

gr.Interface(fn=query_embedding, inputs="text", outputs="text").launch()
