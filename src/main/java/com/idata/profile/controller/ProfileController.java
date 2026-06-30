package com.idata.profile.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F4 全息画像管理。
 * 画像查询（按personId查active版本）、15维度详情展示、
 * 人工直接编辑画像字段（写reviewedAt/reviewerId）。
 * 注意：不提供"触发补全"接口，画像生成只走定时任务
 * （batch.profile.PersonProfileGenerationJob），这里只读不写生成逻辑。
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {
    // TODO: 查询画像详情接口、画像历史版本列表接口、人工编辑字段接口
}
