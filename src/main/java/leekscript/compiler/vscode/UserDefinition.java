package leekscript.compiler.vscode;

public class UserDefinition {
    public int line;
    public int col;
    public String fileName;
    public final String folderName;

    public UserDefinition(int line, int col, String fileName, String folderName) {
        this.line = line;
        this.col = col;
        this.fileName = fileName;
        this.folderName = folderName;
    }
}
