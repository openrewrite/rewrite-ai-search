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
import gradio as gr # 3.23.0
from symspellpy import SymSpell, Verbosity
import re

def split_word(word):
    match = re.match(r"^(.*?[?�]?)([^a-zA-Z?�]*)$", word)
    if match:
        return match.group(1), match.group(2)
    else:
        return word, ''

path = "/app/fr-100k.txt"
sym_spell = SymSpell(max_dictionary_edit_distance=2, prefix_length=7)
if not (sym_spell.load_dictionary(path, term_index=0, count_index=1)):
    raise Exception("Couldn't find the dictionnary.")

def fix_comment_french(comment):
    words = comment.split(" ")
    fixed_words = []
    for word in words:
        word, suffix = split_word(word)
        if word[-2:]=="??":
            word=word[:-1]
            suffix= "?"+suffix
        if "?" in word or "�" in word:
            suggestions = sym_spell.lookup(word, Verbosity.CLOSEST, max_edit_distance=2)
            if len(suggestions)==0:
                fixed_words.append(word)
            else:
                fixed_word = suggestions[0].term
                if word[-1] == "?" and fixed_word[-1] not in ["è", "é", "à", "î", "ô", "ê"]:
                    fixed_word = fixed_word+"?"
                fixed_words.append(fixed_word+suffix)


        else:
            fixed_words.append(word+suffix)
    return " ".join(fixed_words)

gr.Interface(fn=fix_comment_french, inputs=["text"], outputs="text").launch(server_port=7863)