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


#initialize models
from infinity_emb import EngineArgs, AsyncEmbeddingEngine
from infinity_emb import create_server
import uvicorn
import logging
from fastapi.responses import JSONResponse

logging.getLogger("infinity_emb").setLevel(logging.ERROR)

engine_args = EngineArgs(
    model_name_or_path="michaelfeil/bge-small-en-v1.5",
    device="cpu",
    engine="optimum",
    served_model_name="bge-small",
    compile=True,
    batch_size=1
)

fastapi_app = create_server(engine_args_list=[engine_args])
@fastapi_app.head("/embeddings")
def read_root_head():
    return JSONResponse({"message": "Infinity embedding is running"})

uvicorn.run(fastapi_app, host="127.0.0.1", port=7860)
