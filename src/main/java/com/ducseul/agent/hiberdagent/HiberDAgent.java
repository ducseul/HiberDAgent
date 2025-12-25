package com.ducseul.agent.hiberdagent;

import com.ducseul.agent.hiberdagent.advice.CreateStatementAdvice;
import com.ducseul.agent.hiberdagent.advice.PrepareCallAdvice;
import com.ducseul.agent.hiberdagent.advice.PrepareStatementAdvice;
import com.ducseul.agent.hiberdagent.config.AgentConfig;
import com.ducseul.agent.hiberdagent.log.SqlLogWriter;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.sql.Connection;

/**
 * Java Agent that intercepts JDBC calls and logs SQL statements with parameters filled in.
 *
 * Usage: -javaagent:HiberDAgent-1.0.jar
 *
 * Configuration (system properties):
 *   -Dhibernate.agent.slowThresholdMs=5000    (default: 5000)
 *   -Dhibernate.agent.logSqlAlways=true       (default: true)
 *   -Dhibernate.agent.logStack=false          (default: false)
 *   -Dhibernate.agent.maxStackDepth=10        (default: 10)
 *   -Dhibernate.agent.stdout=console          (default: console, or path to file)
 */
public class HiberDAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        final SqlLogWriter logger = SqlLogWriter.getInstance();
        logger.writeLine("[HiberDAgent] Starting SQL logging agent...");
        AgentConfig.printConfig();

        try {
            new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription typeDescription,
                                                  ClassLoader classLoader,
                                                  JavaModule module,
                                                  boolean loaded,
                                                  DynamicType dynamicType) {
                        logger.writeLine("[HiberDAgent] Transformed: " + typeDescription.getName());
                    }

                    @Override
                    public void onError(String typeName,
                                        ClassLoader classLoader,
                                        JavaModule module,
                                        boolean loaded,
                                        Throwable throwable) {
                        logger.writeLine("[HiberDAgent] Error transforming " + typeName + ": " + throwable.getMessage());
                    }
                })
                .type(ElementMatchers.isSubTypeOf(Connection.class)
                    .and(ElementMatchers.not(ElementMatchers.isInterface())))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            ProtectionDomain protectionDomain) {
                        return builder
                            .visit(Advice.to(PrepareStatementAdvice.class)
                                .on(ElementMatchers.named("prepareStatement")
                                    .and(ElementMatchers.takesArgument(0, String.class))))
                            .visit(Advice.to(PrepareCallAdvice.class)
                                .on(ElementMatchers.named("prepareCall")
                                    .and(ElementMatchers.takesArgument(0, String.class))))
                            .visit(Advice.to(CreateStatementAdvice.class)
                                .on(ElementMatchers.named("createStatement")));
                    }
                })
                .installOn(inst);

            logger.writeLine("[HiberDAgent] Agent installed successfully. Ready to intercept JDBC calls.");

        } catch (Exception e) {
            logger.writeLine("[HiberDAgent] Failed to install agent: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        System.out.println("HiberDAgent - JDBC SQL Logging Agent");
        System.out.println("=====================================");
        System.out.println();
        System.out.println("This is a Java Agent. Use it with the -javaagent flag:");
        System.out.println("  java -javaagent:HiberDAgent-1.0.jar -jar your-app.jar");
        System.out.println();
        System.out.println("Configuration (system properties):");
        System.out.println("  -Dhibernate.agent.slowThresholdMs=5000  Log SQL taking longer than N ms (default: 5000)");
        System.out.println("  -Dhibernate.agent.logSqlAlways=true     Log all SQL regardless of duration (default: true)");
        System.out.println("  -Dhibernate.agent.logStack=true         Include stack trace in log (default: false)");
        System.out.println("  -Dhibernate.agent.maxStackDepth=10      Max stack frames to show (default: 10)");
        System.out.println("  -Dhibernate.agent.stdout=console        Output destination: 'console' or file path (default: console)");
        System.out.println();
        System.out.println("Example (log to console):");
        System.out.println("  java -javaagent:HiberDAgent-1.0.jar \\");
        System.out.println("       -Dhibernate.agent.logStack=true \\");
        System.out.println("       -jar your-app.jar");
        System.out.println();
        System.out.println("Example (log to file):");
        System.out.println("  java -javaagent:HiberDAgent-1.0.jar \\");
        System.out.println("       -Dhibernate.agent.stdout=D:\\Temp\\sql.log \\");
        System.out.println("       -jar your-app.jar");
    }
}
