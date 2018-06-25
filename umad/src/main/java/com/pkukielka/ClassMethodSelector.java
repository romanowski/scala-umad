package com.pkukielka;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import javassist.CtMethod;
import javassist.bytecode.AccessFlag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class ClassMethodSelector {
    private final List<ClassMethodDefinition> includes = new ArrayList<ClassMethodDefinition>();
    private final List<ClassMethodDefinition> excludes = new ArrayList<ClassMethodDefinition>();

    static final ClassMethodSelector EMPTY =
            new ClassMethodSelector(ConfigFactory.empty()
                            .withValue("includes",ConfigValueFactory.fromAnyRef(Arrays.asList()))
            );

    ClassMethodSelector(Config config) {
        for (Config include : config.getConfigList("includes")) {
            includes.add(new ClassMethodDefinition(
                    include.getString("class"),
                    include.getString("method")));
        }
        if(config.hasPath("excludes"))
            for (Config exclude : config.getConfigList("excludes")) {
                excludes.add(new ClassMethodDefinition(
                        exclude.getString("class"),
                        exclude.getString("method")
                ));
            }
    }

    boolean shouldTransformClass(final String classNameDotted) {
        return findMatchingDefinition(classNameDotted, null) != null;
    }

    private boolean doesDefinitionMatch(final ClassMethodDefinition definition, final String classNameDotted, final CtMethod editableMethod) {
        return definition.classRegex.matcher(classNameDotted).matches() &&
                (editableMethod == null || (
                        !editableMethod.isEmpty() &&
                        (editableMethod.getModifiers() & AccessFlag.STATIC) == 0) &&
                        definition.methodRegex.matcher(editableMethod.getName()).matches());
    }

    ClassMethodDefinition findMatchingDefinition(final String classNameDotted, final CtMethod editableMethod) {
        // We are excluding only particular methods of the class, never pre-exclude whole class
        if (editableMethod != null) {
            for (final ClassMethodDefinition exclude : excludes) {
                if (doesDefinitionMatch(exclude, classNameDotted, editableMethod)) {
                    return null;
                }
            }
        }
        for (final ClassMethodDefinition include : includes) {
            if (doesDefinitionMatch(include, classNameDotted, editableMethod)) {
                return include;
            }
        }
        return null;
    }

    static class ClassMethodDefinition {
        final Pattern classRegex;
        final Pattern methodRegex;


        ClassMethodDefinition(String classRegex, String methodRegex) {
            this.classRegex = Pattern.compile(classRegex);
            this.methodRegex = Pattern.compile(methodRegex);
        }
    }
}
