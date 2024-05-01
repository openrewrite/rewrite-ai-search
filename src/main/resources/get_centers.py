#
# Copyright 2024 the original author or authors.
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
from transformers import AutoModel, AutoTokenizer, logging # 4.29.2
import gradio as gr # 3.23.0
logging.set_verbosity_error()
from sklearn.cluster import KMeans
import numpy as np
import pandas as pd
import json
from sklearn.metrics import pairwise_distances_argmin_min

def string_to_float_array(str, delimiter=";"):
    return [float(f) for f in str.split(delimiter)]


def get_centers(embeddings, number_of_centers):
    number_of_centers = int(number_of_centers)
    embeddings = json.loads(embeddings)
    df = pd.DataFrame({'embedding': [embedding for embedding in embeddings]})
    df.drop_duplicates("embedding", inplace=True)
    embds = np.array(list(df["embedding"]))

    k = number_of_centers
    if k==-1:
        k = max(10, int(len(df)/200))

    if k > len(df):
        k = len(df)

    kmeans = KMeans(n_clusters=k, random_state=0, n_init=10).fit(embds)

    # Find the closest data points to each centroid
    closest, _ = pairwise_distances_argmin_min(kmeans.cluster_centers_, embds)
    return str(closest.tolist())

gr.Interface(fn=get_centers, inputs=["text", "number"], outputs="text").launch(server_port=7876)
