package com.idata.profile.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F6 检索与知识图谱分析。
 * 实体检索（ES）、多模态检索（文本/图片，三路融合：ES+Neo4j+Milvus）、
 * 关系图谱查询（2跳/多跳）、证据回溯（关联到raw_records）。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {
    // TODO: 全文检索接口、图谱2跳查询接口、向量语义检索接口
}
