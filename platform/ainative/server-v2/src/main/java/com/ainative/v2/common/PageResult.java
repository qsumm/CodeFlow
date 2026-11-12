package com.ainative.v2.common;

import java.util.List;

/** 分页包络,与 v1 PageResult 一致:{list,total,pageNum,pageSize}。 */
public record PageResult<T>(List<T> list, long total, int pageNum, int pageSize) {}
