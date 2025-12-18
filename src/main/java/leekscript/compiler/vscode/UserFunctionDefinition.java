package leekscript.compiler.vscode;

import java.util.ArrayList;

public class UserFunctionDefinition extends UserDefinition {
    public String name;
    public String returnType;
    public ArrayList<UserArgumentDefinition> arguments = new ArrayList<UserArgumentDefinition>();

    public UserFunctionDefinition(int line, int col, String fileName, String folderName, String name,
            String returnType, ArrayList<UserArgumentDefinition> arguments) {
        super(line, col, fileName, folderName);
        this.name = name;
        this.returnType = returnType;
        this.arguments = arguments;
    }

    public String toString() {
        return "Function " + name + " (return type: " + returnType + ") at " + line + ":" + col + " in file "
                + fileName;
    }

}
