package com.example;

import java.util.List;
import java.util.Optional;

public interface MathOperation {
    double execute(double a, double b);
}

public class Calculator implements MathOperation {
    private static final double PI = 3.14159;
    private final List<String> history;

    public Calculator() {
        this.history = new java.util.ArrayList<>();
    }

    @Override
    public double execute(double a, double b) {
        return add(a, b);
    }

    public double add(double a, double b) {
        double result = a + b;
        history.add("add: " + result);
        return result;
    }

    private double subtract(double a, double b) {
        return a - b;
    }

    protected static double multiply(double a, double b) {
        return a * b;
    }

    public Optional<String> getLastOperation() {
        if (history.isEmpty()) return Optional.empty();
        return Optional.of(history.get(history.size() - 1));
    }
}
