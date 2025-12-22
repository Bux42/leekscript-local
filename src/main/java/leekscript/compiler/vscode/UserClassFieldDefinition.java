package leekscript.compiler.vscode;

public class UserClassFieldDefinition extends UserDefinition {
    public String name;
    public String level;
    public String type;
    public boolean isStatic = false;

    public UserClassFieldDefinition(int line, int col, String fileName, String folderName, String name, String level,
            String type) {
        super(line, col, fileName, folderName);
        this.name = name;
        this.level = level;
        this.type = type;
    }

    public String toString() {
        return "Field " + name + " (level: " + level + ", type: " + type + ") at " + line + ":" + col + " in file "
                + fileName + " folder " + folderName;
    }

    public void setStatic(String debugString, boolean debug, boolean isStatic) {
        if (debug) {
            System.out.println(
                    "\t\t[+] " + debugString + " Setting field '" + this.name
                            + "' as static: " + isStatic);
        }
        this.isStatic = isStatic;
    }
}
