package com.lumisight.core.agent.multiagent.service;

import java.util.ArrayList;
import java.util.List;

enum MultiAgentHeuristicSignal {
    LONG_QUESTION("long_question") {
        @Override
        boolean matches(String question) {
            return question.length() >= 120;
        }
    },
    PARALLEL_OR_SPLIT_LANGUAGE("parallel_or_split_language") {
        private final List<String> keywords = List.of("同时", "分别", "并行", "拆解", "多个模块", "多个文件");

        @Override
        boolean matches(String question) {
            return keywords.stream().anyMatch(question::contains);
        }
    },
    FRONTEND_BACKEND_PAIR("frontend_backend_pair") {
        @Override
        boolean matches(String question) {
            return question.contains("前端") && question.contains("后端");
        }
    },
    FIX_WITH_TEST_IMPACT("fix_with_test_impact") {
        @Override
        boolean matches(String question) {
            return question.contains("测试") && (question.contains("修复") || question.contains("重构"));
        }
    };

    private final String signalName;

    MultiAgentHeuristicSignal(String signalName) {
        this.signalName = signalName;
    }

    String signalName() {
        return signalName;
    }

    abstract boolean matches(String question);
    static List<String> collectMatchedSignals(String question) {
        List<String> matchedSignals = new ArrayList<>();
        for (MultiAgentHeuristicSignal signal : values()) {
            if (signal.matches(question)) {
                matchedSignals.add(signal.signalName());
            }
        }
        return matchedSignals;
    }
}
