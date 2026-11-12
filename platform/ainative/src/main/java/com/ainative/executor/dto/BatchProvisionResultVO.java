package com.ainative.executor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 编队一键配齐运行器的整体结果。 */
@Getter
@Setter
public class BatchProvisionResultVO {
    private Long squadId;
    private List<ProvisionItemVO> items = new ArrayList<>();
}
