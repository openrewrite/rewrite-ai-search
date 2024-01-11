import gc
import time
from dataclasses import dataclass
import re
import gradio as gr
from langchain.llms import LlamaCpp


mod_prompt = """You are a software engineer responsible for keeping your source code up to date with the latest third party and open source library. Given a piece of Java code, provide a list of library upgrades you could perform. Only list 3 improvements for modernization."""
vul_prompt = """You are a senior software engineer responsible for spotting vulnerabilities in code. Below is a code which might contain vulnerabilities. Given a piece of Java code, provide a list of the vulnerabilities present.  Only list 3 improvements for security."""

def get_improvements(code_snippet, type_of_improvement, llm, before_after): 
    prompt = mod_prompt if type_of_improvement == "Modernizations" else vul_prompt
    prompt =  before_after[0] +"Here is a code snippet enclosed by backticks, for which you will be asked a question about: \n```"+ code_snippet  +"``` \nHere is the task based on the code above: "+ prompt + before_after[1] 
    return llm(prompt)
    
def parse_improvements(improvements):
    # Define a regular expression pattern to match the enumerated list items
    pattern = r'\b\d+[\.\:\-]\s+(.*?)\s*(?=\b\d+[\.\:\-]|\Z)'
    # Find all matches in the text
    matches = re.findall(pattern, improvements, re.DOTALL)
    return matches



@dataclass
class Config:
    improvement_type: str
    model_name: str
    max_tokens: int = 300


def run(code_snippet: str, c: Config):
    step1_start = time.time_ns()

    model_path = "/Users/juju/models/Q4_K_M/" + c.model_name + ".gguf"
    "<|im_start|>user\n{question}<|im_end|>\n<|im_start|>assistant:"
    before_after = {"codellama": ("[INST]", "[/INST]"), "zephyr": ("<|user|>", "<|assistant|>"), "tinyllama": ("<|im_start|>user\n", "<|im_end|>\n<|im_start|>assistant\n")}
    stops = ["      ", "\n\n", "<|user|>", "[INST]", "[/INST]", "<<SYS>>", "<</SYS>>", "</s>", "<s>"]

    estimated_token_length = (len(code_snippet)//3.5) + 200
    
    llm = LlamaCpp(
        model_path=model_path,
        n_batch=1,
        temperature=0.0,
        max_tokens=c.max_tokens,
        verbose=False,
        f16_kv=True,
        n_ctx=estimated_token_length + c.max_tokens,
        stop=stops + ["}"],
    )
    improvements = get_improvements(
        code_snippet, c.improvement_type, llm, before_after[c.model_name]
    )
    del llm
    gc.collect()

    step1_elapsed = (time.time_ns() - step1_start) / 1e9

    improvements = parse_improvements(improvements)
    if len(improvements)<1:
        return (
            []
        )
    return improvements


# gradio demo
if __name__ == "__main__":
    with gr.Blocks() as demo:
        
        gr.Info("")
        inputs = [
            gr.Text(
                label="Code snippet",
                info="Input the code snippet for which you seek improvement recommendations.",
            )
        ]

        outputs = [gr.Text(label="Improvements recommended")]
        submit_btn = gr.Button("Submit")

        def _run(
            code_snippet,
        ):
            return run(
                code_snippet,
                Config(
                    improvement_type="Modernizations",
                    model_name="codellama",
                ),
            )


        event = submit_btn.click(_run, inputs=inputs, outputs=outputs)
       

    demo.queue()
    demo.launch(share=False, server_port=7864)