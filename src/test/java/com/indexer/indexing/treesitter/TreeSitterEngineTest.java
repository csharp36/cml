package com.indexer.indexing.treesitter;

import com.indexer.indexing.ExtractedSymbol;
import com.indexer.indexing.ExtractedSymbol.Relationship;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreeSitterEngineTest {

    private static TSParserPool parserPool;
    private static TreeSitterEngine engine;

    @BeforeAll
    static void setUp() {
        parserPool = new TSParserPool(2);
        engine = new TreeSitterEngine(parserPool);
    }

    @AfterAll
    static void tearDown() {
        parserPool.close();
    }

    // ========================================================================
    // Java
    // ========================================================================

    @Test
    void parsesJavaClassAndMethods() {
        String source = """
                import java.util.List;
                import java.util.Map;

                public class Calculator {
                    private int value;

                    public Calculator() {
                        this.value = 0;
                    }

                    public static int add(int a, int b) {
                        return a + b;
                    }

                    public int getValue() {
                        return value;
                    }
                }
                """;

        List<ExtractedSymbol> symbols = engine.parse(source, "java");
        assertThat(symbols).isNotNull();

        // Imports
        List<ExtractedSymbol> imports = symbols.stream()
                .filter(s -> "import".equals(s.kind())).toList();
        assertThat(imports).hasSize(2);
        assertThat(imports.get(0).name()).isEqualTo("java.util.List");
        assertThat(imports.get(1).name()).isEqualTo("java.util.Map");

        // Class
        ExtractedSymbol clazz = symbols.stream()
                .filter(s -> "class".equals(s.kind())).findFirst().orElse(null);
        assertThat(clazz).isNotNull();
        assertThat(clazz.name()).isEqualTo("Calculator");
        assertThat(clazz.visibility()).isEqualTo("public");
        assertThat(clazz.signature()).isEqualTo("class Calculator");

        // Constructor
        ExtractedSymbol constructor = symbols.stream()
                .filter(s -> "constructor".equals(s.kind())).findFirst().orElse(null);
        assertThat(constructor).isNotNull();
        assertThat(constructor.name()).isEqualTo("Calculator");
        assertThat(constructor.parentName()).isEqualTo("Calculator");
        assertThat(constructor.visibility()).isEqualTo("public");

        // Methods
        List<ExtractedSymbol> methods = symbols.stream()
                .filter(s -> "method".equals(s.kind())).toList();
        assertThat(methods).hasSize(2);

        ExtractedSymbol addMethod = methods.stream()
                .filter(s -> "add".equals(s.name())).findFirst().orElse(null);
        assertThat(addMethod).isNotNull();
        assertThat(addMethod.parentName()).isEqualTo("Calculator");
        assertThat(addMethod.visibility()).isEqualTo("public");
        assertThat(addMethod.isStatic()).isTrue();
        assertThat(addMethod.signature()).isEqualTo("add(int a, int b)");

        ExtractedSymbol getValueMethod = methods.stream()
                .filter(s -> "getValue".equals(s.name())).findFirst().orElse(null);
        assertThat(getValueMethod).isNotNull();
        assertThat(getValueMethod.parentName()).isEqualTo("Calculator");
        assertThat(getValueMethod.isStatic()).isFalse();
    }

    @Test
    void parsesJavaTypeRelationships() {
        String source = """
                public class Calculator extends BaseCalc implements Computable, Serializable {
                    public int compute() {
                        return 0;
                    }
                }

                public interface Computable {
                    int compute();
                }

                public enum Operation {
                    ADD, SUBTRACT
                }
                """;

        List<ExtractedSymbol> symbols = engine.parse(source, "java");
        assertThat(symbols).isNotNull();

        // Class with relationships
        ExtractedSymbol clazz = symbols.stream()
                .filter(s -> "class".equals(s.kind())).findFirst().orElse(null);
        assertThat(clazz).isNotNull();
        assertThat(clazz.name()).isEqualTo("Calculator");
        assertThat(clazz.relationships()).contains(
                new Relationship("BaseCalc", "extends"));
        assertThat(clazz.relationships()).contains(
                new Relationship("Computable", "implements"));
        assertThat(clazz.relationships()).contains(
                new Relationship("Serializable", "implements"));

        // Interface
        ExtractedSymbol iface = symbols.stream()
                .filter(s -> "interface".equals(s.kind())).findFirst().orElse(null);
        assertThat(iface).isNotNull();
        assertThat(iface.name()).isEqualTo("Computable");
        assertThat(iface.visibility()).isEqualTo("public");

        // Enum
        ExtractedSymbol enumSym = symbols.stream()
                .filter(s -> "enum".equals(s.kind())).findFirst().orElse(null);
        assertThat(enumSym).isNotNull();
        assertThat(enumSym.name()).isEqualTo("Operation");
    }

    // ========================================================================
    // Python
    // ========================================================================

    @Test
    void parsesPythonClassAndFunctions() {
        String source = """
                import os
                from pathlib import Path

                class Animal:
                    def __init__(self, name):
                        self.name = name

                    def speak(self):
                        pass

                class Dog(Animal):
                    def speak(self):
                        return "Woof"

                def helper(x):
                    return x + 1
                """;

        List<ExtractedSymbol> symbols = engine.parse(source, "python");
        assertThat(symbols).isNotNull();

        // Imports
        List<ExtractedSymbol> imports = symbols.stream()
                .filter(s -> "import".equals(s.kind())).toList();
        assertThat(imports).hasSize(2);

        // Classes
        List<ExtractedSymbol> classes = symbols.stream()
                .filter(s -> "class".equals(s.kind())).toList();
        assertThat(classes).hasSize(2);

        ExtractedSymbol animal = classes.stream()
                .filter(s -> "Animal".equals(s.name())).findFirst().orElse(null);
        assertThat(animal).isNotNull();
        assertThat(animal.signature()).isEqualTo("class Animal");

        ExtractedSymbol dog = classes.stream()
                .filter(s -> "Dog".equals(s.name())).findFirst().orElse(null);
        assertThat(dog).isNotNull();
        assertThat(dog.relationships()).contains(
                new Relationship("Animal", "extends"));

        // Methods (inside classes)
        List<ExtractedSymbol> methods = symbols.stream()
                .filter(s -> "method".equals(s.kind())).toList();
        assertThat(methods).hasSizeGreaterThanOrEqualTo(3); // __init__, speak x2

        // Top-level function
        ExtractedSymbol helper = symbols.stream()
                .filter(s -> "function".equals(s.kind()) && "helper".equals(s.name())).findFirst().orElse(null);
        assertThat(helper).isNotNull();
        assertThat(helper.parentName()).isNull();
        assertThat(helper.signature()).isEqualTo("helper(x)");
    }

    // ========================================================================
    // TypeScript
    // ========================================================================

    @Test
    void parsesTypeScriptClassAndFunctions() {
        String source = """
                import { Component } from '@angular/core';
                import * as utils from './utils';

                export interface Greeter {
                    greet(): string;
                }

                export class AppComponent implements Greeter {
                    private name: string;

                    constructor(name: string) {
                        this.name = name;
                    }

                    greet(): string {
                        return `Hello, ${this.name}`;
                    }
                }

                export function helper(x: number): number {
                    return x + 1;
                }
                """;

        List<ExtractedSymbol> symbols = engine.parse(source, "typescript");
        assertThat(symbols).isNotNull();

        // Imports
        List<ExtractedSymbol> imports = symbols.stream()
                .filter(s -> "import".equals(s.kind())).toList();
        assertThat(imports).hasSize(2);
        assertThat(imports.get(0).name()).isEqualTo("@angular/core");
        assertThat(imports.get(1).name()).isEqualTo("./utils");

        // Interface
        ExtractedSymbol iface = symbols.stream()
                .filter(s -> "interface".equals(s.kind())).findFirst().orElse(null);
        assertThat(iface).isNotNull();
        assertThat(iface.name()).isEqualTo("Greeter");

        // Class
        ExtractedSymbol clazz = symbols.stream()
                .filter(s -> "class".equals(s.kind())).findFirst().orElse(null);
        assertThat(clazz).isNotNull();
        assertThat(clazz.name()).isEqualTo("AppComponent");
        assertThat(clazz.relationships()).contains(
                new Relationship("Greeter", "implements"));

        // Methods
        List<ExtractedSymbol> methods = symbols.stream()
                .filter(s -> "method".equals(s.kind())).toList();
        assertThat(methods).hasSizeGreaterThanOrEqualTo(2); // constructor + greet

        // Function
        ExtractedSymbol helperFn = symbols.stream()
                .filter(s -> "function".equals(s.kind()) && "helper".equals(s.name())).findFirst().orElse(null);
        assertThat(helperFn).isNotNull();
        assertThat(helperFn.signature()).contains("helper");
    }

    // ========================================================================
    // Go
    // ========================================================================

    @Test
    void parsesGoStructsAndFunctions() {
        String source = """
                package main

                import (
                    "fmt"
                    "math"
                )

                type Calculator struct {
                    Value int
                }

                type Adder interface {
                    Add(a, b int) int
                }

                func (c *Calculator) Add(a, b int) int {
                    return a + b
                }

                func main() {
                    fmt.Println("hello")
                }
                """;

        List<ExtractedSymbol> symbols = engine.parse(source, "go");
        assertThat(symbols).isNotNull();

        // Imports
        List<ExtractedSymbol> imports = symbols.stream()
                .filter(s -> "import".equals(s.kind())).toList();
        assertThat(imports).hasSize(2);
        assertThat(imports.get(0).name()).isEqualTo("fmt");
        assertThat(imports.get(1).name()).isEqualTo("math");

        // Struct (mapped to "class")
        List<ExtractedSymbol> classes = symbols.stream()
                .filter(s -> "class".equals(s.kind())).toList();
        assertThat(classes).hasSizeGreaterThanOrEqualTo(2); // Calculator struct + Adder interface

        ExtractedSymbol calc = classes.stream()
                .filter(s -> "Calculator".equals(s.name())).findFirst().orElse(null);
        assertThat(calc).isNotNull();
        assertThat(calc.signature()).isEqualTo("class Calculator");

        // Method with receiver
        ExtractedSymbol addMethod = symbols.stream()
                .filter(s -> "method".equals(s.kind()) && "Add".equals(s.name())).findFirst().orElse(null);
        assertThat(addMethod).isNotNull();
        assertThat(addMethod.parentName()).isEqualTo("Calculator");
        assertThat(addMethod.signature()).isEqualTo("Add(a, b int)");

        // Function
        ExtractedSymbol mainFn = symbols.stream()
                .filter(s -> "function".equals(s.kind()) && "main".equals(s.name())).findFirst().orElse(null);
        assertThat(mainFn).isNotNull();
    }

    // ========================================================================
    // C
    // ========================================================================

    @Test
    void parsesCFunctionsAndStructs() {
        String source = """
                #include <stdio.h>
                #include "myheader.h"

                struct Point {
                    int x;
                    int y;
                };

                enum Direction {
                    NORTH, SOUTH, EAST, WEST
                };

                int add(int a, int b) {
                    return a + b;
                }

                static void helper(void) {
                    // do nothing
                }
                """;

        List<ExtractedSymbol> symbols = engine.parse(source, "c");
        assertThat(symbols).isNotNull();

        // Includes (imports)
        List<ExtractedSymbol> imports = symbols.stream()
                .filter(s -> "import".equals(s.kind())).toList();
        assertThat(imports).hasSize(2);
        assertThat(imports.get(0).name()).isEqualTo("stdio.h");
        assertThat(imports.get(1).name()).isEqualTo("myheader.h");

        // Struct (mapped to "class")
        ExtractedSymbol point = symbols.stream()
                .filter(s -> "class".equals(s.kind()) && "Point".equals(s.name())).findFirst().orElse(null);
        assertThat(point).isNotNull();
        assertThat(point.signature()).isEqualTo("class Point");

        // Enum
        ExtractedSymbol direction = symbols.stream()
                .filter(s -> "enum".equals(s.kind()) && "Direction".equals(s.name())).findFirst().orElse(null);
        assertThat(direction).isNotNull();

        // Functions
        ExtractedSymbol addFn = symbols.stream()
                .filter(s -> "function".equals(s.kind()) && "add".equals(s.name())).findFirst().orElse(null);
        assertThat(addFn).isNotNull();
        assertThat(addFn.signature()).isEqualTo("add(int a, int b)");

        ExtractedSymbol helperFn = symbols.stream()
                .filter(s -> "function".equals(s.kind()) && "helper".equals(s.name())).findFirst().orElse(null);
        assertThat(helperFn).isNotNull();
        assertThat(helperFn.isStatic()).isTrue();
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    void returnsNullForUnsupportedLanguage() {
        List<ExtractedSymbol> result = engine.parse("some code", "rust");
        assertThat(result).isNull();
    }

    @Test
    void handlesEmptySource() {
        List<ExtractedSymbol> result = engine.parse("", "java");
        assertThat(result).isEmpty();

        result = engine.parse("   \n  \n", "java");
        assertThat(result).isEmpty();

        result = engine.parse(null, "java");
        assertThat(result).isEmpty();
    }
}
