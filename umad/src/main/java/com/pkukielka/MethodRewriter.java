package com.pkukielka;

import com.typesafe.config.Config;
import javassist.CannotCompileException;
import javassist.CtMethod;

public abstract class MethodRewriter {
  private ClassMethodSelector selector;
  private boolean enabled;
  protected Config config;

  public MethodRewriter(Config config){
    enabled = config.hasPath("enabled") && config.getBoolean("enabled");
    if (enabled) selector = new ClassMethodSelector(config);
    else selector = ClassMethodSelector.EMPTY;
    this.config = config;
  }

  public void applyOnMethod(final CtMethod editableMethod,
                                     String dottedName) throws CannotCompileException {
    ClassMethodSelector.ClassMethodDefinition md = selector.findMatchingDefinition(dottedName, editableMethod);
    if (md != null) {
      editMethod(editableMethod, md.ifCalledFrom, dottedName);
    }
  }


  protected abstract void editMethod(final CtMethod editableMethod, String ifCalledFrom, String dottedName) throws CannotCompileException;

  public boolean shouldTransformClass(final String classNameDotted) {
    return enabled && selector.shouldTransformClass(classNameDotted);
  }

    public boolean isEnabled() {
        return enabled;
    }
}
