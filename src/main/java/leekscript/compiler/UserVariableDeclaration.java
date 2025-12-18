package leekscript.compiler;

import leekscript.compiler.bloc.AbstractLeekBlock;

public class UserVariableDeclaration extends UserDefinition {
    public String name;
    public String type;
    private AbstractLeekBlock parentBlockRef = null;

    public UserVariableDeclaration(int line, int col, String fileName, String folderName, String name, String type) {
        super(line, col, fileName, folderName);
        this.name = name;
        this.type = type;
    }

    public String toString() {
        return "Variable " + name + " (type: " + type + ") at " + line + ":" + col + " in file " + fileName;
    }

    /**
     * Sets the reference to the parent block (e.g., ForBlock) where this variable
     * is declared.
     * 
     * @param parentBlockRef
     */
    public void setParentBlockRef(AbstractLeekBlock parentBlockRef) {
        this.parentBlockRef = parentBlockRef;
    }

    /**
     * Gets the reference to the parent block where this variable is declared.
     * 
     * @return the parent block reference
     */
    public AbstractLeekBlock getParentBlockRef() {
        return parentBlockRef;
    }

    /**
     * Clears the reference to the parent block.
     */

    public void clearParentBlockRef() {
        this.parentBlockRef = null;
    }
}
