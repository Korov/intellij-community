// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.dependency.java;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.dependency.Node;
import org.jetbrains.jps.dependency.Usage;
import org.jetbrains.jps.dependency.diff.DiffCapable;
import org.jetbrains.jps.dependency.diff.Difference;
import org.jetbrains.jps.javac.Iterators;
import org.jetbrains.org.objectweb.asm.Type;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

public final class JvmMethod extends ProtoMember implements DiffCapable<JvmMethod, JvmMethod.Diff> {
  private final Iterable<TypeRepr> myArgTypes;
  private final Set<ParamAnnotation> myParamAnnotations;
  private final Set<TypeRepr.ClassType> myExceptions;

  public JvmMethod(
    JVMFlags flags, String signature, String name, String descriptor,
    @NotNull Iterable<TypeRepr.ClassType> annotations, Set<ParamAnnotation> parameterAnnotations,
    String[] exceptions, Object defaultValue) {

    super(flags, signature, name, TypeRepr.getType(Type.getReturnType(descriptor)), annotations, defaultValue);
    myParamAnnotations = parameterAnnotations;
    myExceptions = Iterators.collect(Iterators.map(Iterators.asIterable(exceptions), s -> new TypeRepr.ClassType(s)), new HashSet<>());
    myArgTypes = TypeRepr.getTypes(Type.getArgumentTypes(descriptor));
  }

  public boolean isConstructor() {
    return "<init>".equals(getName());
  }

  @Override
  public MethodUsage createUsage(JvmNodeReferenceID owner) {
    return new MethodUsage(owner, getName(), getDescriptor());
  }

  public BiPredicate<Node<?, ?>, Usage> createUsageQuery(JvmNodeReferenceID owner) {
    String thisMethodName = getName();
    return (n,u) -> u instanceof MethodUsage && owner.equals(u.getElementOwner()) && Objects.equals(((MethodUsage)u).getName(), thisMethodName);
  }

  public Set<ParamAnnotation> getParamAnnotations() {
    return myParamAnnotations;
  }

  public Set<TypeRepr.ClassType> getExceptions() {
    return myExceptions;
  }

  public Iterable<TypeRepr> getArgTypes() {
    return myArgTypes;
  }

  public boolean isSameByJavaRules(JvmMethod other) {
    return getName().equals(other.getName()) && Iterators.equals(myArgTypes, other.myArgTypes);
  }

  @Override
  public boolean isSame(DiffCapable<?, ?> other) {
    if (!(other instanceof JvmMethod)) {
      return false;
    }
    JvmMethod that = (JvmMethod)other;
    return Objects.equals(getType(), that.getType()) && isSameByJavaRules(that);
  }

  @Override
  public int diffHashCode() {
    return 31 * (31 * Iterators.hashCode(myArgTypes) + getType().hashCode()) + getName().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof JvmMethod && isSame((JvmMethod)obj);
  }

  @Override
  public int hashCode() {
    return diffHashCode();
  }

  @Override
  public JvmMethod.Diff difference(JvmMethod past) {
    return new Diff(past);
  }

  public final class Diff extends ProtoMember.Diff<JvmMethod> {

    public Diff(JvmMethod past) {
      super(past);
    }

    @Override
    public boolean unchanged() {
      return super.unchanged() && paramAnnotations().unchanged() && exceptions().unchanged();
    }

    public Specifier<ParamAnnotation, ?> paramAnnotations() {
      return Difference.diff(myPast.getParamAnnotations(), getParamAnnotations());
    }

    public Specifier<TypeRepr.ClassType, ?> exceptions() {
      return Difference.diff(myPast.getExceptions(), getExceptions());
    }
  }

  public String getDescriptor() {
    final StringBuilder buf = new StringBuilder();

    buf.append("(");

    for (TypeRepr t : myArgTypes) {
      buf.append(t.getDescriptor());
    }

    buf.append(")");
    buf.append(getType().getDescriptor());

    return buf.toString();
  }
}
