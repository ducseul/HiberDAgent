package com.ducseul.agent.hiberdagent;

import com.ducseul.agent.hiberdagent.advice.CreateStatementAdvice;
import com.ducseul.agent.hiberdagent.advice.PrepareCallAdvice;
import com.ducseul.agent.hiberdagent.advice.PrepareStatementAdvice;
import com.ducseul.agent.hiberdagent.config.AgentConfig;
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
 */
public class HiberDAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[HiberDAgent] Starting SQL logging agent...");
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
                        System.out.println("[HiberDAgent] Transformed: " + typeDescription.getName());
                    }

                    @Override
                    public void onError(String typeName,
                                        ClassLoader classLoader,
                                        JavaModule module,
                                        boolean loaded,
                                        Throwable throwable) {
                        System.err.println("[HiberDAgent] Error transforming " + typeName + ": " + throwable.getMessage());
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

            System.out.println("[HiberDAgent] Agent installed successfully. Ready to intercept JDBC calls.");

        } catch (Exception e) {
            System.err.println("[HiberDAgent] Failed to install agent: " + e.getMessage());
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
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -javaagent:HiberDAgent-1.0.jar \\");
        System.out.println("       -Dhibernate.agent.logStack=true \\");
        System.out.println("       -jar your-app.jar");
    }
}
