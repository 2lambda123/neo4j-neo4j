/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.neo4j.shell;

import java.util.Objects;
import org.assertj.core.api.Condition;

public class Conditions {
    public static Condition<String> contains(String expected) {
        Objects.requireNonNull(expected);
        return new Condition<>(s -> s.contains(expected), "String contains \"%s\"", expected);
    }

    public static Condition<String> emptyString() {
        return new Condition<>(String::isEmpty, "String is empty");
    }

    public static Condition<String> endsWith(String expected) {
        Objects.requireNonNull(expected);
        return new Condition<>(s -> s.endsWith(expected), "String ends with \"%s\"", expected);
    }

    public static <T> Condition<T> is(T expected) {
        return equalTo(expected);
    }

    public static <T> Condition<T> equalTo(T expected) {
        Objects.requireNonNull(expected);
        return new Condition<>(expected::equals, "Equals %s", expected);
    }

    public static Condition<String> startsWith(String expected) {
        Objects.requireNonNull(expected);
        return new Condition<>(s -> s.startsWith(expected), "String starts with \"%s\"", expected);
    }
}