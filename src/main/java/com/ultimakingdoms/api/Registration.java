package com.ultimakingdoms.api;

@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override
    void close();
}
