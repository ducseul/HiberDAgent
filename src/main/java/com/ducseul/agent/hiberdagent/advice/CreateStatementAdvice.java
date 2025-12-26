package com.ducseul.agent.hiberdagent.advice;

import com.ducseul.agent.hiberdagent.wrapper.StatementWrapper;
import net.bytebuddy.asm.Advice;

import java.sql.Statement;

/**
 * Byte Buddy Advice for intercepting Connection.createStatement() calls.
 * Wraps the returned Statement with our logging proxy.
 */
public class CreateStatementAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Return(readOnly = false) Statement returned) {

        if (returned == null) {
            return;
        }

        returned = StatementWrapper.wrap(returned);
    }
}
