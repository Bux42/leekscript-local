package leekscript.runner;

public interface OperationsProfiler {

	void enter(String name, String file, int line);

	void exit();

	void location(String file, int line);

	void record(long operations);
}
