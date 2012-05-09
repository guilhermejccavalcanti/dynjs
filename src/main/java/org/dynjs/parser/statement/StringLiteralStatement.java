/**
 *  Copyright 2012 Douglas Campos, and individual contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.dynjs.parser.statement;

import me.qmx.jitescript.CodeBlock;
import org.antlr.runtime.tree.Tree;
import org.dynjs.parser.Statement;

import static me.qmx.jitescript.CodeBlock.*;

public class StringLiteralStatement extends BaseStatement implements Statement {

    private final String literal;

    public StringLiteralStatement(final Tree tree, String literal) {
        super(tree);
        this.literal = literal;
    }

    @Override
    public CodeBlock getCodeBlock() {
        return newCodeBlock()
                .ldc(literal);
    }
}
