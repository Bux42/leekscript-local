package leekscript.compiler.vscode;

import java.util.ArrayList;
import java.util.List;

public class UserClassDefinition extends UserDefinition {
    public final String name;
    public String parentName = null;
    public boolean isStatic = false;

    public List<UserClassFieldDefinition> fields = new ArrayList<>();
    public List<UserClassMethodDefinition> methods = new ArrayList<>();
    public List<UserClassMethodDefinition> constructors = new ArrayList<>();

    public UserClassDefinition(int line, int col, String fileName, String folderName, String name, String parentName) {
        super(line, col, fileName, folderName);
        this.name = name;
        this.parentName = parentName;
    }

    public void addField(UserClassFieldDefinition field) {
        fields.add(field);
    }

    public void addMethod(UserClassMethodDefinition method) {
        methods.add(method);
    }

    public void addConstructor(UserClassMethodDefinition constructor) {
        constructors.add(constructor);
    }

    public UserClassMethodDefinition getMethodByName(String methodName) {
        for (UserClassMethodDefinition method : methods) {
            if (method.name.equals(methodName)) {
                return method;
            }
        }
        return null;
    }

    public UserClassFieldDefinition getFieldByName(String fieldName) {
        for (UserClassFieldDefinition field : fields) {
            if (field.name.equals(fieldName)) {
                return field;
            }
        }
        return null;
    }

    public void setParentClassName(String parentName) {
        this.parentName = parentName;
    }
}
