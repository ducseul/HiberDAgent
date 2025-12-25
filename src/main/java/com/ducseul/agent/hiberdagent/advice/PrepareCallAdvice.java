package com.ducseul.agent.hiberdagent.advice;

import com.ducseul.agent.hiberdagent.log.SqlLogWriter;
import com.ducseul.agent.hiberdagent.wrapper.PreparedStatementWrapper;
import net.bytebuddy.asm.Advice;

import java.sql.CallableStatement;

/**
 * Byte Buddy Advice for intercepting Connection.prepareCall() calls.
 * Wraps the returned CallableStatement with our logging proxy.
 */
public class PrepareCallAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) String sql,
            @Advice.Return(readOnly = false) CallableStatement returned,
            @Advice.Thrown Throwable thrown) {

        if (thrown != null || returned == null) {
            return;
        }

        try {
            returned = PreparedStatementWrapper.wrapCallable(returned, sql);
        } catch (Throwable t) {
            // Log and swallow exceptions to avoid breaking application behavior
            SqlLogWriter.getInstance().writeError("Failed to wrap CallableStatement for SQL: " + sql, t);
        }
    }
}
