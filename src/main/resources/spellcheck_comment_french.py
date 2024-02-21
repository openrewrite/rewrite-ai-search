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
accents = ['à', 'â', 'æ', 'ç', 'é', 'è', 'ê', 'ë', 'î', 'ï', 'ô', 'œ', 'ù', 'û', 'ü', 'ÿ', 'À', 'Â', 'Æ', 'Ç', 'É', 'È', 'Ê', 'Ë', 'Î', 'Ï', 'Ô', 'Œ', 'Ù', 'Û', 'Ü', 'Ÿ']
def split_word(word):
    match = re.match(r"^(.*?[?�]?)([^a-zA-Z?�]*)$", word)
    if match:
        return match.group(1), match.group(2)
    else:
        return word, ''

path = "/app/fr-1M.txt"
sym_spell = SymSpell(max_dictionary_edit_distance=2, prefix_length=7)
if not (sym_spell.load_dictionary(path, term_index=1, count_index=2, separator="\t")):
    raise Exception("Couldn't find the dictionnary.")

def fix_comment_french(comments):
    comment_lines = comments.split("\n")
    def fix_single_line(comment):
        words = comment.split(" ")
        fixed_words = []
        for index, word in enumerate(words):
            word, suffix = split_word(word)
            if word[-2:]=="??":
                word=word[:-1]
                suffix= "?"+suffix
            if "?" in word or "�" in word:
                if word == "?" and (index==len(words)-1 or words[index+1][0].isupper()):
                    fixed_words.append(word)
                    continue
                suggestions = sym_spell.lookup(word, Verbosity.CLOSEST, max_edit_distance=word.count("?")+word.count("�"))
                if len(suggestions)==0:
                    fixed_words.append(word)
                else:
                    fixed_word = suggestions[0].term
                    if word[-1] == "?" and fixed_word[-1] not in accents:
                        if word[:-1].lower()==fixed_word.lower():
                            fixed_word = word +"?"
                        else: fixed_word = fixed_word+"?"
                    if not any(accent in fixed_word for accent in accents):
                        fixed_words.append(word)
                    else: fixed_words.append(fixed_word+suffix)

            else:
                fixed_words.append(word+suffix)
        return " ".join(fixed_words)
    lines = [fix_single_line(comment) for comment in comment_lines]
    return "\n".join(lines)

gr.Interface(fn=fix_comment_french, inputs=["text"], outputs="text").launch(server_port=7866)