package com.lumisight.tools.vector.spi.impl;

import com.lumisight.tools.vector.spi.SymbolDocGenerator;
import org.springframework.stereotype.Component;

@Component
public class TemplateSymbolDocGenerator implements SymbolDocGenerator {

    @Override
    public String generate(String qualifiedName, String symbolSignature, String codeContext) {
        String signature = symbolSignature == null ? "" : symbolSignature;
        String preview = codeContext == null ? "" : codeContext;
        if (preview.length() > 300) {
            preview = preview.substring(0, 300);
        }
        return "模板摘要（非 AI 生成）\n"
                + "symbol=" + qualifiedName + "\n"
                + "signature=" + signature + "\n"
                + "codePreview=" + preview;
    }
}
