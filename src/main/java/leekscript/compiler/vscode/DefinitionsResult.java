package leekscript.compiler.vscode;

import com.alibaba.fastjson.JSONArray;

public class DefinitionsResult {
    public JSONArray classes = new JSONArray();
    public JSONArray functions = new JSONArray();
    public JSONArray globals = new JSONArray();
    public JSONArray variables = new JSONArray();

    private Exception exception = null;

    public void debugDefinedNames() {
        if (!classes.isEmpty()) {
            System.out.println("Defined Classes:");
            for (Object _class : classes) {
                System.out.println(" - " + ((UserClassDefinition) _class).name);
                for (Object method : ((UserClassDefinition) _class).methods) {
                    System.out.println("    - Method: " + ((UserClassMethodDefinition) method).name);
                }
            }
        }
        if (!functions.isEmpty()) {
            System.out.println("Defined Functions:");
            for (Object function : functions) {
                System.out.println(" - " + ((UserFunctionDefinition) function).name);
            }
        }
        if (!variables.isEmpty()) {
            System.out.println("Defined Variables:");
            for (Object variable : variables) {
                UserVariableDeclaration v = (UserVariableDeclaration) variable;
                System.out.println(" - " + v.name + " type: " + v.type);
            }
        }
        if (!globals.isEmpty()) {
            System.out.println("Defined Globals:");
            for (Object variable : globals) {
                System.out.println(" - " + ((UserVariableDeclaration) variable).name);
            }
        }
    }

    public void setException(Exception exception) {
        System.out.println("Setting exception in DefinitionsResult: " + exception.getMessage());
        this.exception = exception;
    }
}
