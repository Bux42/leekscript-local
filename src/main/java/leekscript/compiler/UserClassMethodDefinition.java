package leekscript.compiler;

import java.util.ArrayList;

public class UserClassMethodDefinition extends UserDefinition {
    // since a single method can be declared with multiple signatures (overloading)
    public String name;
    public ArrayList<UserArgumentDefinition> arguments = new ArrayList<UserArgumentDefinition>();
    public String returnType;
    // what about multiple return types?
    // what about if static, private, etc.?
    public boolean isStatic = false;
    public String level = "public";

    public UserClassMethodDefinition(int line, int col, String fileName, String folderName, String name,
            String returnType) {
        super(line, col, fileName, folderName);
        this.name = name;
        this.returnType = returnType;
    }

    public void addArgument(String debugString, boolean debug, UserArgumentDefinition argument) {
        if (debug) {
            System.out.println(
                    "\t\t[+] " + debugString + " Adding argument '" + argument.name
                            + "' of type " + argument.type
                            + " to method "
                            + this.name);
        }
        arguments.add(argument);
    }

    public void setStatic(String debugString, boolean debug, boolean isStatic) {
        if (debug) {
            System.out.println(
                    "\t\t[+] " + debugString + " Setting method '" + this.name
                            + "' as static: " + isStatic);
        }
        this.isStatic = isStatic;
    }

    public void setLevel(String debugString, boolean debug, String level) {
        if (debug) {
            System.out.println(
                    "\t\t[+] " + debugString + " Setting method '" + this.name
                            + "' level to: " + level);
        }
        this.level = level;
    }
}
