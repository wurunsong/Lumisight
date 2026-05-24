package com.lumisight.tools.vector.service;

public interface SymbolDocGenerator {

    String generate(String qualifiedName, String symbolSignature, String codeContext);
}
