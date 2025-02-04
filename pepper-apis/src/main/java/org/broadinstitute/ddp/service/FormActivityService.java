package org.broadinstitute.ddp.service;

import java.util.ArrayList;
import java.util.List;

import org.broadinstitute.ddp.db.dto.UserActivityInstanceSummary;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.json.form.BlockVisibility;
import org.broadinstitute.ddp.model.activity.definition.FormActivityDef;
import org.broadinstitute.ddp.pex.PexException;
import org.broadinstitute.ddp.pex.PexInterpreter;
import org.jdbi.v3.core.Handle;

public class FormActivityService {

    private final PexInterpreter interpreter;

    public FormActivityService(PexInterpreter interpreter) {
        this.interpreter = interpreter;
    }

    /**
     * Get and evaluate visibility only for blocks that has an associated conditional expression.
     *
     * @param handle       the jdbi handle
     * @param userGuid     the user guid
     * @param instanceGuid the form instance guid
     * @return list of block visibilities
     * @throws DDPException if pex evaluation error
     */
    public List<BlockVisibility> getBlockVisibilitiesAndEnabled(Handle handle, UserActivityInstanceSummary instanceSummary,
                                                                FormActivityDef def, String userGuid, String operatorGuid,
                                                                String instanceGuid) {
        List<BlockVisibility> visibilities = new ArrayList<>();
        for (var block : def.getAllToggleableBlocks()) {
            Boolean shown = evaluate(handle, block.getBlockGuid(), block.getShownExpr(), instanceSummary, userGuid,
                    operatorGuid, instanceGuid);
            Boolean enabled = evaluate(handle, block.getBlockGuid(), block.getEnabledExpr(), instanceSummary, userGuid,
                    operatorGuid, instanceGuid);
            if (shown != null || enabled != null) {
                visibilities.add(new BlockVisibility(
                        block.getBlockGuid(),
                        shown == null || shown,
                        enabled == null || enabled));
            }
        }
        return visibilities;
    }

    private Boolean evaluate(Handle handle, String blockGuid, String expr, UserActivityInstanceSummary instanceSummary,
                                               String userGuid, String operatorGuid,
                                               String instanceGuid) {
        if (expr != null) {
            try {
                return interpreter.eval(expr, handle, userGuid, operatorGuid, instanceGuid, instanceSummary);
            } catch (PexException e) {
                String msg = String.format("Error evaluating pex expression for form activity instance %s and block %s: `%s`",
                        instanceGuid, blockGuid, expr);
                throw new DDPException(msg, e);
            }
        }
        return null;
    }

}
