package leekscript.compiler;

import java.util.HashMap;
import java.util.Map;

public class UserCodeDefinitionContext {
    public final int line;
    public final int column;
    public final DefinitionsResult result;
    public final AIFile aiFile;

    public Map<String, UserClassDefinition> definedClassNames = new HashMap<>();
    public Map<String, UserFunctionDefinition> definedFunctionNames = new HashMap<>();

    public boolean debug = true;

    public UserCodeDefinitionContext(int line, int column, DefinitionsResult result, AIFile aiFile) {
        this.line = line;
        this.column = column;
        this.result = result;
        this.aiFile = aiFile;
    }

    public UserClassDefinition getClassDefinition(String className) {
        return definedClassNames.get(className);
    }

    public UserVariableDeclaration getLatestDeclaredVariable() {
        if (result.variables.isEmpty()) {
            return null;
        }
        return (UserVariableDeclaration) result.variables.get(result.variables.size() - 1);
    }

    public void clearVariableParentBlockReferences() {
        for (Object variable : result.variables) {
            if (((UserVariableDeclaration) variable).getParentBlockRef() != null) {
                if (debug) {
                    System.out.println(
                            "[clearVariableParentBlockReferences] (this is not supposed to happen) Clearing parentBlock reference for variable: "
                                    + ((UserVariableDeclaration) variable).name);
                }
                ((UserVariableDeclaration) variable).clearParentBlockRef();
            }
        }
    }

    public void addClass(String debugString, UserClassDefinition classDefinition) {
        if (debug) {
            System.out.println("[+] " + debugString + " Adding class: " + classDefinition.name);
        }
        result.classes.add(classDefinition);
        definedClassNames.put(classDefinition.name, classDefinition);
    }

    public void addClassMethod(String debugString, UserClassDefinition classDefinition,
            UserClassMethodDefinition methodDefinition) {
        if (debug) {
            System.out.println("\t[+] " + debugString + " Adding method: '" + methodDefinition.name + "' to class: "
                    + classDefinition.name);
        }
        classDefinition.addMethod(methodDefinition);
    }

    public void addClassConstructor(String debugString, UserClassDefinition classDefinition,
            UserClassMethodDefinition constructorDefinition) {
        if (debug) {
            System.out.println("[+] " + debugString + " Adding constructor to class: " + classDefinition.name);
        }
        classDefinition.addConstructor(constructorDefinition);
    }

    public void addVariable(String debugString, UserVariableDeclaration variable) {
        if (debug) {
            boolean hasParentBlock = variable.getParentBlockRef() != null;
            System.out.println("[+] " + debugString + " Adding variable: '" + variable.name + "' of type "
                    + variable.type + (hasParentBlock ? " (WITH PARENT)" : " (NO PARENT)"));
        }
        result.variables.add(variable);
    }

    public void removeVariable(String debugString, UserVariableDeclaration variable) {
        if (debug) {
            boolean hasParentBlock = variable.getParentBlockRef() != null;
            System.out.println("[-] " + debugString + " Removing variable: " + variable.name + " of type "
                    + variable.type + (hasParentBlock ? " (WITH PARENT)" : " (NO PARENT)"));
        }
        result.variables.remove(variable);
    }

    public String userCursorLocationToString() {
        return "User cursor at line: " + line + " column: " + column + " in file: " + aiFile.getName();
    }

    public boolean userCursorLineBefore(int targetLine) {
        return this.line < targetLine;
    }

    public boolean userCursorLineBeforeOrEqual(int targetLine) {
        return this.line <= targetLine;
    }

    public boolean userCursorLineAfter(int targetLine) {
        return this.line > targetLine;
    }

    public boolean userCursorLineAfterOrEqual(int targetLine) {
        return this.line >= targetLine;
    }

    public boolean userCursorInSameFile(AIFile file) {
        return this.aiFile == file;
    }
}
