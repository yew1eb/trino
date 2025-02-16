/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.json.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.type.Type;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class IrMemberAccessor
        extends IrPathNode
{
    private final IrPathNode base;

    // object member key or Optional.empty for wildcard member accessor
    private final Optional<String> key;

    @JsonCreator
    public IrMemberAccessor(@JsonProperty("base") IrPathNode base, @JsonProperty("key") Optional<String> key, @JsonProperty("type") Optional<Type> type)
    {
        super(type);
        this.base = requireNonNull(base, "member accessor base is null");
        this.key = requireNonNull(key, "key is null");
    }

    @Override
    protected <R, C> R accept(IrJsonPathVisitor<R, C> visitor, C context)
    {
        return visitor.visitIrMemberAccessor(this, context);
    }

    @JsonProperty
    public IrPathNode getBase()
    {
        return base;
    }

    @JsonProperty
    public Optional<String> getKey()
    {
        return key;
    }
}
