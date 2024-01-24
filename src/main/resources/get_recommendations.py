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
from moderne_recommendation_agent import app
import gradio as gr
from functools import partial
# app.start_gradio()
inputs = [
    gr.Textbox(
        label="Code snippet",
        placeholder="Input the code snippet for which you seek improvement recommendations."
    ),
    gr.Number(
        label="n_batch"
    )
]

outputs = [
    gr.Textbox(label="Improvements recommended")
]

run_partial = partial(app._run, "/CACHE/codellama.gguf")

interface = gr.Interface(
    fn=run_partial,
    inputs=inputs,
    outputs=outputs,
)


interface.launch(share=False, server_port=7866)