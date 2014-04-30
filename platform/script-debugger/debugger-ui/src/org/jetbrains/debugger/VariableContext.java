package org.jetbrains.debugger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface VariableContext {
  @NotNull
  EvaluateContext getEvaluateContext();

  /**
   * Parent variable name if this context is {@link org.jetbrains.debugger.VariableView}
   */
  @Nullable
  String getName();

  @Nullable
  VariableContext getParent();

  boolean watchableAsEvaluationExpression();

  @NotNull
  DebuggerViewSupport getViewSupport();

  @NotNull
  MemberFilter getMemberFilter();

  @Nullable
  Scope getScope();
}