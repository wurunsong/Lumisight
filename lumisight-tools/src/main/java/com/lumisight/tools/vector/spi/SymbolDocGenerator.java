package com.lumisight.tools.vector.spi;

public interface SymbolDocGenerator {

    String generate(String qualifiedName, String symbolSignature, String codeContext);
}
