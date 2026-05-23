package com.lumisight.skills;

import com.lumisight.common.LayerInfo;
import org.springframework.stereotype.Service;

@Service
public class SkillService {

    public LayerInfo describe() {
        return new LayerInfo("lumisight-skills", "负责 Skill 加载、匹配、策略注入与版本管理");
    }
}
