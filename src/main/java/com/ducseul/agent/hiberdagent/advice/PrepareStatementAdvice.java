package com.ducseul.agent.hiberdagent.advice;

import com.ducseul.agent.hiberdagent.log.SqlLogWriter;
import com.ducseul.agent.hiberdagent.wrapper.PreparedStatementWrapper;
import net.bytebuddy.asm.Advice;

import java.sql.PreparedStatement;

/**
 * Byte Buddy Advice for intercepting Connection.prepareStatement() calls.
 * Wraps the returned PreparedStatement with our logging proxy.
 */
public class PrepareStatementAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) String sql,
            @Advice.Return(readOnly = false) PreparedStatement returned,
            @Advice.Thrown Throwable thrown) {

        if (thrown != null || returned == null) {
            return;
        }

        try {
            returned = PreparedStatementWrapper.wrap(returned, sql);
        } catch (Throwable t) {
            // Log and swallow exceptions to avoid breaking application behavior
            SqlLogWriter.getInstance().writeError("Failed to wrap PreparedStatement for SQL: " + sql, t);
        }
    }
}
