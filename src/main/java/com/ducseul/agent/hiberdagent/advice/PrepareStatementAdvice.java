package com.ducseul.agent.hiberdagent.advice;

import com.ducseul.agent.hiberdagent.wrapper.PreparedStatementWrapper;
import net.bytebuddy.asm.Advice;

import java.sql.PreparedStatement;

/**
 * Byte Buddy Advice for intercepting Connection.prepareStatement() calls.
 * Wraps the returned PreparedStatement with our logging proxy.
 */
public class PrepareStatementAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) String sql,
            @Advice.Return(readOnly = false) PreparedStatement returned) {

        if (returned == null) {
            return;
        }

        returned = PreparedStatementWrapper.wrap(returned, sql);
    }
}
