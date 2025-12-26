package com.ducseul.agent.hiberdagent.advice;

import com.ducseul.agent.hiberdagent.wrapper.PreparedStatementWrapper;
import net.bytebuddy.asm.Advice;

import java.sql.CallableStatement;

/**
 * Byte Buddy Advice for intercepting Connection.prepareCall() calls.
 * Wraps the returned CallableStatement with our logging proxy.
 */
public class PrepareCallAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) String sql,
            @Advice.Return(readOnly = false) CallableStatement returned) {

        if (returned == null) {
            return;
        }

        returned = PreparedStatementWrapper.wrapCallable(returned, sql);
    }
}
