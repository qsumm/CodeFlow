package com.ainative.evolution;

import com.ainative.json.JSON;
import com.ainative.json.JSONObject;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class EvolutionDraftSourceResolver {

    private final RuleBasedDraftFallback ruleFallback;

    public EvolutionDraftSourceResolver(RuleBasedDraftFallback ruleFallback) {
        this.ruleFallback = ruleFallback;
    }

    public String resolve(EvolutionOrchestrateCommand cmd, EvidenceLedgerEventCommand event) {
        if (!blank(cmd.getSuggestedPatchJson())) {
            return cmd.getSuggestedPatchJson();
        }
        if (!blank(cmd.getDraftDeltaJson())) {
            return patchFromDraftDelta(cmd.getDraftDeltaJson());
        }
        return ruleFallback.draft(cmd, event);
    }

    private String patchFromDraftDelta(String draftDeltaJson) {
        try {
            JSONObject delta = JSON.parseObject(draftDeltaJson);
            if (delta == null || delta.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            JSONObject patch = delta.getJSONObject("patch");
            if (patch == null) {
                patch = delta.getJSONObject("draftPatch");
            }
            if (patch == null || patch.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            return JSON.toJSONString(patch);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
