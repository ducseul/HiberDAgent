package com.ducseul.agent.hiberdagent.advice;

import com.ducseul.agent.hiberdagent.log.SqlLogWriter;
import com.ducseul.agent.hiberdagent.wrapper.StatementWrapper;
import net.bytebuddy.asm.Advice;

import java.sql.Statement;

/**
 * Byte Buddy Advice for intercepting Connection.createStatement() calls.
 * Wraps the returned Statement with our logging proxy.
 */
public class CreateStatementAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Return(readOnly = false) Statement returned,
            @Advice.Thrown Throwable thrown) {

        if (thrown != null || returned == null) {
            return;
        }

        try {
            returned = StatementWrapper.wrap(returned);
        } catch (Throwable t) {
            // Log and swallow exceptions to avoid breaking application behavior
            SqlLogWriter.getInstance().writeError("Failed to wrap Statement", t);
        }
    }
}
