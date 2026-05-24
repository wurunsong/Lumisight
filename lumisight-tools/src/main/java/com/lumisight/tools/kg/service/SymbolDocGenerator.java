package com.lumisight.tools.kg.service;

public interface SymbolDocGenerator {

    String generate(String qualifiedName, String symbolSignature, String codeContext);
}
