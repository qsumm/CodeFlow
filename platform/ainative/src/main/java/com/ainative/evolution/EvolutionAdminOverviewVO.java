package com.ainative.evolution;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class EvolutionAdminOverviewVO {
    private List<EvolutionProposalDO> proposals;
    private List<BayesianEvidenceDO> evidence;
}
