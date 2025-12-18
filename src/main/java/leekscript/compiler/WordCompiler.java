package leekscript.compiler;

import java.util.ArrayList;
import java.math.BigInteger;
import java.util.HashSet;

import leekscript.common.AccessLevel;
import leekscript.common.Error;
import leekscript.common.FunctionType;
import leekscript.common.Type;
import leekscript.compiler.AnalyzeError.AnalyzeErrorLevel;
import leekscript.compiler.bloc.AbstractLeekBlock;
import leekscript.compiler.bloc.AnonymousFunctionBlock;
import leekscript.compiler.bloc.ClassMethodBlock;
import leekscript.compiler.bloc.ConditionalBloc;
import leekscript.compiler.bloc.DoWhileBlock;
import leekscript.compiler.bloc.ForBlock;
import leekscript.compiler.bloc.ForeachBlock;
import leekscript.compiler.bloc.ForeachKeyBlock;
import leekscript.compiler.bloc.FunctionBlock;
import leekscript.compiler.bloc.MainLeekBlock;
import leekscript.compiler.bloc.WhileBlock;
import leekscript.compiler.exceptions.LeekCompilerException;
import leekscript.compiler.expression.Expression;
import leekscript.compiler.expression.LeekAnonymousFunction;
import leekscript.compiler.expression.LeekArray;
import leekscript.compiler.expression.LeekBigInteger;
import leekscript.compiler.expression.LeekBoolean;
import leekscript.compiler.expression.LeekCompoundType;
import leekscript.compiler.expression.LeekExpression;
import leekscript.compiler.expression.LeekExpressionException;
import leekscript.compiler.expression.LeekFunctionCall;
import leekscript.compiler.expression.LeekInteger;
import leekscript.compiler.expression.LeekInterval;
import leekscript.compiler.expression.LeekMap;
import leekscript.compiler.expression.LeekNull;
import leekscript.compiler.expression.LeekObject;
import leekscript.compiler.expression.LeekParameterType;
import leekscript.compiler.expression.LeekParenthesis;
import leekscript.compiler.expression.LeekReal;
import leekscript.compiler.expression.LeekSet;
import leekscript.compiler.expression.LeekString;
import leekscript.compiler.expression.LeekType;
import leekscript.compiler.expression.LeekVariable;
import leekscript.compiler.expression.LeekVariable.VariableType;
import leekscript.compiler.expression.LegacyLeekArray;
import leekscript.compiler.expression.Operators;
import leekscript.compiler.instruction.BlankInstruction;
import leekscript.compiler.instruction.ClassDeclarationInstruction;
import leekscript.compiler.instruction.LeekBreakInstruction;
import leekscript.compiler.instruction.LeekContinueInstruction;
import leekscript.compiler.instruction.LeekExpressionInstruction;
import leekscript.compiler.instruction.LeekGlobalDeclarationInstruction;
import leekscript.compiler.instruction.LeekReturnInstruction;
import leekscript.compiler.instruction.LeekVariableDeclarationInstruction;
import leekscript.compiler.vscode.UserArgumentDefinition;
import leekscript.compiler.vscode.UserClassDefinition;
import leekscript.compiler.vscode.UserClassMethodDefinition;
import leekscript.compiler.vscode.UserCodeDefinitionContext;
import leekscript.compiler.vscode.UserFunctionDefinition;
import leekscript.compiler.vscode.UserVariableDeclaration;

public class WordCompiler {

	private MainLeekBlock mMain;
	private AbstractLeekBlock mCurentBlock;
	private AbstractLeekBlock mCurrentFunction;
	private ClassDeclarationInstruction mCurrentClass;
	private LexicalParserTokenStream mTokens;
	private int mLine;
	private AIFile mAI = null;
	private final int version;
	private final Options options;

	/* User definition variables */
	public UserCodeDefinitionContext userDefinitionsContext = null;

	public WordCompiler(AIFile ai, int version, Options options) {
		mAI = ai;
		this.version = version;
		this.options = options;
	}

	private void parse() throws LeekCompilerException {
		if (!mAI.hasBeenParsed()) {
			var parser = new LexicalParser(mAI, version);
			mAI.setTokenStream(parser.parse(error -> addError(error)));
		}
		mTokens = mAI.getTokenStream();
	}

	public void setUserDefinitionContext(UserCodeDefinitionContext userDefinitionsContext) {
		this.userDefinitionsContext = userDefinitionsContext;
	}

	public boolean isInterrupted() {
		return System.currentTimeMillis() - mMain.getCompiler().getAnalyzeStart() > IACompiler.TIMEOUT_MS;
	}

	public void checkInterrupted() throws LeekCompilerException {
		if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
	}

	public void readCode() throws LeekCompilerException {

		firstPass();

		// Classes pré-définies :
		// System.out.println(mMain.getDefinedClasses());

		secondPass();
	}

	/**
	 * Recherche des includes, globales, classes et fonctions utilisateur
	 */
	public void firstPass() throws LeekCompilerException {
		try {
			parse();
			mTokens.reset();

			while (mTokens.hasMoreTokens()) {

				if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

				if (mTokens.get().getWord().equals("include")) {
					var token = mTokens.eat();
					// On vérifie qu'on est dans le bloc principal
					if (!mCurentBlock.equals(mMain)) throw new LeekCompilerException(mTokens.get(), Error.INCLUDE_ONLY_IN_MAIN_BLOCK);
					// On récupere l'ia
					if (mTokens.eat().getType() != TokenType.PAR_LEFT) throw new LeekCompilerException(mTokens.get(), Error.OPENING_PARENTHESIS_EXPECTED);

					if (mTokens.get().getType() != TokenType.VAR_STRING) throw new LeekCompilerException(mTokens.get(), Error.AI_NAME_EXPECTED);

					String iaName = mTokens.eat().getWord();
					iaName = iaName.substring(1, iaName.length() - 1);

					if (!mMain.includeAIFirstPass(this, iaName)) {
						var location = new Location(token.getLocation(), mTokens.get().getLocation());
						addError(new AnalyzeError(location, AnalyzeErrorLevel.ERROR, Error.AI_NOT_EXISTING, new String[] { iaName }));
					}

					if (mTokens.eat().getType() != TokenType.PAR_RIGHT) throw new LeekCompilerException(mTokens.get(), Error.CLOSING_PARENTHESIS_EXPECTED);

				} else if (mTokens.get().getType() == TokenType.GLOBAL) {
					mTokens.skip();
					eatType(true, false);
					var global = mTokens.eat();
					// System.out.println("global = " + global.getWord() + " " + global.getLine());
					if (!isGlobalAvailable(global) || mMain.hasDeclaredGlobal(global.getWord())) {
						addError(new AnalyzeError(global, AnalyzeErrorLevel.ERROR, Error.VARIABLE_NAME_UNAVAILABLE));
					} else {
						mMain.addGlobal(global.getWord());
					}
					if (mTokens.get().getWord().equals("=")) {
						mTokens.skip();
						readExpression(true);
					}
					while (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.VIRG) {
						mTokens.skip();
						global = mTokens.eat();
						if (!isGlobalAvailable(global) || mMain.hasDeclaredGlobal(global.getWord())) {
							addError(new AnalyzeError(global, AnalyzeErrorLevel.ERROR, Error.VARIABLE_NAME_UNAVAILABLE));
						} else {
							mMain.addGlobal(global.getWord());
						}
						if (mTokens.get().getWord().equals("=")) {
							mTokens.skip();
							readExpression(true);
						}
					}
				} else if (mTokens.get().getType() == TokenType.FUNCTION) {
					var functionToken = mTokens.eat();
					var funcName = mTokens.eat();
					if (funcName.getWord().equals("(") || funcName.getWord().equals("<")) {
						continue;
					}
					if (!isAvailable(funcName, false)) {
						throw new LeekCompilerException(mTokens.get(), Error.FUNCTION_NAME_UNAVAILABLE);
					}
					if (mTokens.eat().getType() != TokenType.PAR_LEFT) {
						if (functionToken.getWord().equals("Function")) {
							// Déclaration de type
							mTokens.unskip();
							continue;
						} else {
							addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.OPENING_PARENTHESIS_EXPECTED));
						}
					}
					int param_count = 0;
					var parameters = new HashSet<String>();
					while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.PAR_RIGHT) {

						if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

						if (mTokens.get().getType() == TokenType.OPERATOR && mTokens.get().getWord().equals("@")) {
							mTokens.skip();
						}
						// if (mTokens.get().getType() != TokenType.STRING) {
						// addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARAMETER_NAME_EXPECTED));
						// }
						var parameter = mTokens.eat();
						// if (parameters.contains(parameter.getWord())) {
						// 	throw new LeekCompilerException(parameter, Error.PARAMETER_NAME_UNAVAILABLE);
						// }
						parameters.add(parameter.getWord());
						param_count++;

						if (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.VIRG) {
							mTokens.skip();
						}
					}
					if (mTokens.hasMoreTokens() && mTokens.eat().getType() != TokenType.PAR_RIGHT) {
						addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS));
					}

					mMain.addFunctionDeclaration(funcName.getWord(), param_count);

				} else if (mTokens.get().getWord().equals("class")) {

					mTokens.skip();
					if (mTokens.hasMoreTokens()) {
						var className = mTokens.eat();

						if (className.getType() == TokenType.STRING) {

							if (mMain.getDefinedClass(className.getWord()) != null) {
								throw new LeekCompilerException(className, Error.VARIABLE_NAME_UNAVAILABLE, new String[] { className.getWord() });
							}

							var clazz = new ClassDeclarationInstruction(className, mLine, mAI, false, getMainBlock());

							if (userDefinitionsContext != null) {
								clazz.checkUserClassDefinitions("firstPass", this);
							}

							mMain.defineClass(clazz);
						}
					}
				} else {
					mTokens.skip();
				}
			}

		} catch (IndexOutOfBoundsException e) {
			e.printStackTrace(System.out);
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.END_OF_SCRIPT_UNEXPECTED));
		}
	}

	public void secondPass() throws LeekCompilerException {
		mTokens = this.mAI.getTokenStream();
		assert mTokens != null : "tokens are null";
		mTokens.reset();

		// Vraie compilation
		while (mTokens.hasMoreTokens()) {

			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

			// On vérifie les instructions en cours
			if (mCurentBlock instanceof DoWhileBlock && !((DoWhileBlock) mCurentBlock).hasAccolade() && mCurentBlock.isFull()) {
				DoWhileBlock do_block = (DoWhileBlock) mCurentBlock;
				mCurentBlock = mCurentBlock.endInstruction();
				dowhileendBlock(do_block);
				mTokens.skip();
			} else {
				if (userDefinitionsContext != null && mCurentBlock.isFull() && mCurentBlock.hasAccolade()) {

					// get latest variable
					UserVariableDeclaration lastVar = userDefinitionsContext.getLatestDeclaredVariable();
					if (lastVar != null) {
						// check forBlock
						if (lastVar.getParentBlockRef() != null) {
							AbstractLeekBlock lastVarParentBlockRef = lastVar.getParentBlockRef();

							if (userDefinitionsContext.debug) {
								System.out.println(
										"[secondPassGetDefinitions] endInstruction 2 lastVar " + lastVar.name
												+ " has forBlock, equals: " + (lastVarParentBlockRef == mCurentBlock));
							}

							if (lastVarParentBlockRef == mCurentBlock) {
								Location debugLocation = mTokens.get().getLocation();

								int blockEndLine = debugLocation.getStartLine();
								int blockEndColumn = debugLocation.getStartColumn();

								int blockStartLine = lastVarParentBlockRef.getLocation().getStartLine();
								int blockStartColumn = lastVarParentBlockRef.getLocation().getStartColumn();

								int userCol = userDefinitionsContext.column - 1;
								int userLine = userDefinitionsContext.line;

								int lastVarLine = lastVar.line;
								int lastVarColumn = lastVar.col;

								// TODO: check lastVar line & col
								// if cursor is before variable, remove it from suggestions
								// Check block end line/column
								// if cursor is after block end line/column, remove it from suggestions

								if (userDefinitionsContext.debug) {
									System.out.println(
											"[secondPassGetDefinitions] endInstruction 4 user cursor at line "
													+ userLine
													+ " column " + userCol + " block ends at line " + blockEndLine
													+ " column " + blockEndColumn + " block starts at line "
													+ blockStartLine + " column " + blockStartColumn
													+ " lastVar at line "
													+ lastVarLine + " column " + lastVarColumn);
								}

								// no autocomplete if cursor is before variable declaration line
								if (userLine <= lastVarLine) {
									if (userDefinitionsContext.debug) {
										System.out.println(
												"[secondPassGetDefinitions] 5 cursor is before variable declaration, out of scope");
									}
									lastVar.clearParentBlockRef();
									userDefinitionsContext.result.variables.remove(lastVar);
									continue;
								}
							}
						}
					}
				}

				if (userDefinitionsContext != null) {
					if (userDefinitionsContext.debug) {
						System.out.println(
								"[secondPassGetDefinitions] 6 mCurentBlock hasAccolade " + mCurentBlock.hasAccolade() +
										" parent  "
										+ (mCurentBlock.getParent() != null
												? mCurentBlock.getParent().getClass().getSimpleName()
												: "null")
										+
										" isFull " + mCurentBlock.isFull());
					}
				}

				mCurentBlock = mCurentBlock.endInstruction();

			}

			if (!mTokens.hasMoreTokens()) {
				System.out.println("[secondPassGetDefinitions] 7 No more tokens");
				break;
			}

			// Puis on lit l'instruction
			compileWord();
		}
		while (mCurentBlock.getParent() != null && !mCurentBlock.hasAccolade()) {

			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

			if (mCurentBlock instanceof DoWhileBlock) {
				DoWhileBlock do_block = (DoWhileBlock) mCurentBlock;
				mCurentBlock = mCurentBlock.endInstruction();
				dowhileendBlock(do_block);
				mTokens.skip();
			} else {
				if (mCurentBlock.endInstruction() == mCurentBlock) {
					throw new LeekCompilerException(mTokens.get(), Error.NO_BLOC_TO_CLOSE);
				}
				System.out.println(
						"[secondPassGetDefinitions] 7 mCurentBlock hasAccolade " + mCurentBlock.hasAccolade() +
								" parent  "
								+ (mCurentBlock.getParent() != null
										? mCurentBlock.getParent().getClass().getSimpleName()
										: "null")
								+
								" isFull " + mCurentBlock.isFull());
				mCurentBlock = mCurentBlock.endInstruction();
			}
		}
		if (!mMain.equals(mCurentBlock)) throw new LeekCompilerException(mTokens.get(), Error.OPEN_BLOC_REMAINING);

		// } catch (IndexOutOfBoundsException e) {
		// 	e.printStackTrace(System.out);
		// 	addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.END_OF_SCRIPT_UNEXPECTED));
		// }
	}

	public void analyze() throws LeekCompilerException {
		// Analyse sémantique
		mCurentBlock = mMain;
		setCurrentFunction(mMain);
		mMain.preAnalyze(this);
		mMain.analyze(this);
	}

	private void compileWord() throws LeekCompilerException {
		mLine = mTokens.get().getLocation().getStartLine();
		mMain.addInstruction();
		Token word = mTokens.get();
		if (word.getType() == TokenType.END_INSTRUCTION) {
			// mCurentBlock.addInstruction(this, new BlankInstruction());
			if (userDefinitionsContext != null) {
				if (userDefinitionsContext.debug) {
					System.out.println("[compileWord] END_INSTRUCTION at "
							+ word.getLocation().toString());
				}
			}
			mCurentBlock.setFull(true);
			mTokens.skip();
			return;
		} else if (word.getType() == TokenType.ACCOLADE_RIGHT) {
			// Fermeture de bloc
			if (userDefinitionsContext != null) {
				if (userDefinitionsContext.debug) {
					System.out.println("[compileWord] ACCOLADE_RIGHT at "
							+ word.getLocation().toString());
				}
			}
			if (!mCurentBlock.hasAccolade() || mCurentBlock.getParent() == null) {
				// throw new LeekCompilerException(word, Error.NO_BLOC_TO_CLOSE);
				addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.NO_BLOC_TO_CLOSE));
			} else {
				if (mCurentBlock instanceof DoWhileBlock) {
					DoWhileBlock do_block = (DoWhileBlock) mCurentBlock;
					mCurentBlock.checkEndBlock();
					mCurentBlock = mCurentBlock.getParent();
					mTokens.skip();
					dowhileendBlock(do_block);
				} else {
					if (userDefinitionsContext != null) {
						if (userDefinitionsContext.debug) {
							System.out.println(
									"[compileWord] Closing block: " + mCurentBlock.getClass().getSimpleName());
						}
						while (userDefinitionsContext.result.variables.size() > 0) {
							// get latest variable
							UserVariableDeclaration lastVar = userDefinitionsContext.getLatestDeclaredVariable();

							// check forBlock
							if (lastVar.getParentBlockRef() != null && lastVar.getParentBlockRef() == mCurentBlock) {
								Location currentBlockLocation = mTokens.get().getLocation();

								if (userDefinitionsContext.debug) {
									System.out.println(
											"[compileWord] Closing matching block: "
													+ mCurentBlock.getClass().getSimpleName()
													+ " for variable " + lastVar.name);

									System.out.println(
											"[compileWord] currentBlockLocation: " + currentBlockLocation);
								}
								int userCol = userDefinitionsContext.column - 1;
								int userLine = userDefinitionsContext.line;

								if (userLine > currentBlockLocation.getEndLine()) {
									if (userDefinitionsContext.debug) {
										System.out.println(
												"[compileWord] Cursor is after block, removing variable "
														+ lastVar.name + " from suggestions");
									}
									userDefinitionsContext.removeVariable("compileWord", lastVar);
									// userDefinitionsContext.result.variables.remove(lastVar);
								}
								lastVar.clearParentBlockRef();
							} else {
								if (userDefinitionsContext.debug) {
									System.out.println(
											"[compileWord] No matching block for variable: "
													+ lastVar.name + " breaking loop " + " with parentBlockRef: " +
													(lastVar.getParentBlockRef() != null
															? lastVar.getParentBlockRef().getClass().getSimpleName()
															: "null"));
								}
								break;
							}
						}
					}
					mCurentBlock.checkEndBlock();
					mCurentBlock = mCurentBlock.getParent();
				}
			}
			mTokens.skip();
			return;

		} else if (word.getType() == TokenType.VAR) {

			// Déclaration de variable
			mTokens.skip();
			variableDeclaration(null);
			return;

		} else if (word.getType() == TokenType.GLOBAL) {

			// Déclaration de variable
			globalDeclaration();
			return;

		} else if (word.getType() == TokenType.RETURN) {

			var token = mTokens.eat();
			var optional = false;
			if (mTokens.get().getWord().equals("?")) {
				optional = true;
				mTokens.eat();
			}
			Expression exp = null;
			if (mTokens.get().getType() != TokenType.END_INSTRUCTION && mTokens.get().getType() != TokenType.ACCOLADE_RIGHT) {
				exp = readExpression();
			}
			if (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.END_INSTRUCTION) {
				mTokens.skip();
			}
			mCurentBlock.addInstruction(this, new LeekReturnInstruction(token, exp, optional));
			return;

		} else if (word.getType() == TokenType.FOR) {

			forBlock();
			return;

		} else if (word.getType() == TokenType.WHILE) {

			whileBlock();
			return;

		} else if (word.getType() == TokenType.IF) {

			ifBlock();
			return;

		} else if (version >= 2 && getCurrentBlock() instanceof MainLeekBlock && word.getType() == TokenType.CLASS) {

			// Déclaration de classe
			mTokens.skip();
			classDeclaration();
			return;

		} else if (word.getType() == TokenType.BREAK) {

			if (!mCurentBlock.isBreakable()) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.BREAK_OUT_OF_LOOP));
			}
			mTokens.skip();
			if (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.END_INSTRUCTION) {
				mTokens.skip();
			}
			mCurentBlock.addInstruction(this, new LeekBreakInstruction(word));

			return;

		} else if (word.getType() == TokenType.CONTINUE) {

			if (!mCurentBlock.isBreakable()) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.CONTINUE_OUT_OF_LOOP));
			}
			var token = mTokens.eat();
			if (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.END_INSTRUCTION) {
				mTokens.skip();
			}
			mCurentBlock.addInstruction(this, new LeekContinueInstruction(token));
			return;

 		} else if (word.getType() == TokenType.FUNCTION) {

			var functionToken = mTokens.eat();
			if (mTokens.get().getWord().equals("<")) { // Début d'un type
				mTokens.unskip();
			} else { // Vraie fonction
				functionBlock(functionToken);
				return;
			}

		} else if (word.getType() == TokenType.ELSE) {

			elseBlock();
			return;

		} else if (word.getType() == TokenType.DO) {

			doWhileBlock();
			return;

		} else if (word.getType() == TokenType.INCLUDE) {

			var token = mTokens.eat(); // include
			includeBlock(token);
			return;

		}

		var save = mTokens.getPosition();
		var type = eatType(true, false);
		if (type != null) {
			// Déclaration de variable ou expression ?
			if (mTokens.get().getType() == TokenType.STRING) {
				// Déclaration de variable Class a = ...
				variableDeclaration(type);
			} else {
				// Class.toto, on revient d'un token et on parse une expression
				mTokens.setPosition(save);
				var exp = readExpression();
				if (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.END_INSTRUCTION) {
					mTokens.skip();
				}
				mCurentBlock.addInstruction(this, new LeekExpressionInstruction(exp));
			}
			return;
		} else {
			var exp = readExpression();
			if (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.END_INSTRUCTION) {
				mTokens.skip();
			}
			mCurentBlock.addInstruction(this, new LeekExpressionInstruction(exp));
		}
	}

	public void writeJava(String className, JavaWriter writer, String AIClass, Options options) {
		mMain.writeJavaCode(writer, className, AIClass, options);
	}

	private void includeBlock(Token token) throws LeekCompilerException {

		if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

		// On vérifie qu'on est dans le bloc principal
		if (!mCurentBlock.equals(mMain)) throw new LeekCompilerException(mTokens.get(), Error.INCLUDE_ONLY_IN_MAIN_BLOCK);
		// On récupere l'ia
		if (mTokens.eat().getType() != TokenType.PAR_LEFT) throw new LeekCompilerException(mTokens.get(), Error.OPENING_PARENTHESIS_EXPECTED);

		if (mTokens.get().getType() != TokenType.VAR_STRING) throw new LeekCompilerException(mTokens.get(), Error.AI_NAME_EXPECTED);

		String iaName = mTokens.eat().getWord();
		iaName = iaName.substring(1, iaName.length() - 1);
		if (!mMain.includeAI(this, iaName)) {
			var location = new Location(token.getLocation(), mTokens.get().getLocation());
			addError(new AnalyzeError(location, AnalyzeErrorLevel.ERROR, Error.AI_NOT_EXISTING, new String[] { iaName }));
		}

		if (mTokens.eat().getType() != TokenType.PAR_RIGHT) throw new LeekCompilerException(mTokens.get(), Error.CLOSING_PARENTHESIS_EXPECTED);
	}

	public boolean checkAddUserFunctionDefinitions(WordCompiler compiler, Token functionToken, FunctionBlock block) {
		if (compiler.userDefinitionsContext != null) {
			if (compiler.userDefinitionsContext.debug) {
				System.out
						.println("[checkUserFunctionDefinitions] Checking function " + functionToken.getWord());
			}
			if (!compiler.userDefinitionsContext.definedFunctionNames.containsKey(functionToken.getWord())) {
				if (compiler.userDefinitionsContext.debug) {
					System.out
							.println("[checkUserFunctionDefinitions] Registered function " + functionToken.getWord() +
									" return type: " + block.getType().returnType());

				}

				ArrayList<LeekVariableDeclarationInstruction> funcParams = block.getParameterDeclarations();
				ArrayList<UserArgumentDefinition> arguments = new ArrayList<UserArgumentDefinition>();

				for (int i = 0; i < funcParams.size(); i++) {
					LeekVariableDeclarationInstruction paramInst = funcParams.get(i);
					Location paramLLocation = paramInst.getLocation();

					// add argument to function definition
					arguments.add(new UserArgumentDefinition(paramInst.getName(), paramInst.getType().name));
					if (compiler.userDefinitionsContext.debug) {
						System.out.println(
								"[+] [checkAdddUserFunctionDefinitions] Adding argument " + paramInst.getName() +
										" of type " + paramInst.getType().name + " to function "
										+ functionToken.getWord());
					}

					// add argument to user variables with parent block ref
					UserVariableDeclaration varDecl = new UserVariableDeclaration(
							paramLLocation.getStartLine(),
							paramLLocation.getStartColumn(),
							paramLLocation.getFile().getName(),
							paramLLocation.getFile().getFolder().getName(),
							paramInst.getName(),
							paramInst.getType().name);
					varDecl.setParentBlockRef(block);
					compiler.userDefinitionsContext.result.variables.add(varDecl);
				}

				Location loc = block.getLocation();
				int line = loc.getStartLine();
				int column = loc.getStartColumn();
				String fileName = loc.getFile().getName();
				String folderName = loc.getFile().getFolder().getName();

				UserFunctionDefinition functionDef = new UserFunctionDefinition(line, column,
						fileName, folderName,
						functionToken.getWord(), block.getType().returnType().name, arguments);

				compiler.userDefinitionsContext.result.functions.add(functionDef);
				compiler.userDefinitionsContext.definedFunctionNames.put(functionToken.getWord(), functionDef);

				if (compiler.userDefinitionsContext.debug) {
					System.out
							.println("[checkAdddUserFunctionDefinitions] Registered function " + functionToken.getWord()
									+
									" in user definitions context at "
									+ line + ":" + column + " in file " + fileName + " in folder "
									+ folderName);
				}
			}
			return true;
		}
		return false;
	}

	private void functionBlock(Token functionToken) throws LeekCompilerException {
		// Déclaration de fonction utilisateur
		if (!mCurentBlock.equals(mMain)) {
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.FUNCTION_ONLY_IN_MAIN_BLOCK));
		}
		// Récupération du nom de la fonction
		if (mTokens.get().getType() != TokenType.STRING) {
			throw new LeekCompilerException(mTokens.get(), Error.FUNCTION_NAME_EXPECTED);
		}
		Token funcName = mTokens.eat();
		if (!isAvailable(funcName, false)) {
			throw new LeekCompilerException(mTokens.get(), Error.FUNCTION_NAME_UNAVAILABLE);
		}
		if (mTokens.eat().getType() != TokenType.PAR_LEFT) {
			if (functionToken.getWord().equals("Function")) {
				// Déclaration de type
				mTokens.unskip();
				return;
			} else {
				throw new LeekCompilerException(mTokens.get(), Error.OPENING_PARENTHESIS_EXPECTED);
			}
		}

		var previousFunction = mCurrentFunction;
		FunctionBlock block = new FunctionBlock(mCurentBlock, mMain, funcName);
		mCurentBlock = block;
		setCurrentFunction(block);
		while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.PAR_RIGHT) {

			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

			var type = eatType(false, false);

			boolean is_reference = false;
			if (mTokens.get().getType() == TokenType.OPERATOR && mTokens.get().getWord().equals("@")) {
				is_reference = true;
				if (getVersion() >= 2) {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.WARNING, Error.REFERENCE_DEPRECATED));
				}
				mTokens.skip();
			}

			Token parameter = null;
			if (mTokens.get().getType() != TokenType.STRING) {
				if (type != null && type.getClass() == LeekType.class) {
					parameter = type.token;
					type = null;
				} else {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARAMETER_NAME_EXPECTED));
					mTokens.skip();
				}
			} else {
				parameter = mTokens.get();
				mTokens.skip();
			}

			if (mTokens.get().getType() == TokenType.VIRG) {
				mTokens.skip();
			}
			if (parameter != null) {
				block.addParameter(this, parameter, is_reference, type);
			}
		}
		if (mTokens.eat().getType() != TokenType.PAR_RIGHT) {
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS));
		}

		if (mTokens.get().getType() == TokenType.ARROW) {
			mTokens.skip();

			var returnType = eatType(false, true);
			if (returnType == null) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.TYPE_EXPECTED));
			} else {
				block.setReturnType(returnType.getType());
			}
		}

		// On regarde s'il y a des accolades
		if (mTokens.eat().getType() != TokenType.ACCOLADE_LEFT) {
			throw new LeekCompilerException(mTokens.get(), Error.OPENING_CURLY_BRACKET_EXPECTED);
		}
		mMain.addFunction(block);
		checkAddUserFunctionDefinitions(this, funcName, block);

		setCurrentFunction(previousFunction);
	}

	private LeekType eatType(boolean first, boolean mandatory) throws LeekCompilerException {

		var type = eatOptionalType(first, mandatory);
		if (type == null) return null;

		while (mTokens.get().getWord().equals("|")) {

			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

			var pipe = mTokens.eat();

			var type2 = eatOptionalType(false, true);
			if (type2 == null) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.TYPE_EXPECTED));
			} else {
				type = new LeekCompoundType(type, type2, pipe);
			}
			pipe.setExpression(type);
		}
		return type;
	}

	private LeekType eatOptionalType(boolean first, boolean mandatory) throws LeekCompilerException {
		var type = eatPrimaryType(first, mandatory);
		if (type == null) return null;

		if (mTokens.get().getWord().equals("?")) {
			var question = mTokens.eat();
			type = new LeekCompoundType(type, new LeekType(question, Type.NULL), question);
		}
		return type;
	}

	private LeekType eatPrimaryType(boolean first, boolean mandatory) throws LeekCompilerException {
		var word = mTokens.get().getWord();
		if (word.equals("void")) return new LeekType(mTokens.eat(), Type.VOID);
		if (!first && word.equals("null")) return new LeekType(mTokens.eat(), Type.NULL);
		if (word.equals("boolean")) return new LeekType(mTokens.eat(), Type.BOOL);
		if (word.equals("any")) return new LeekType(mTokens.eat(), Type.ANY);
		if (word.equals("integer")) return new LeekType(mTokens.eat(), Type.INT);
		if (word.equals("big_integer")) return new LeekType(mTokens.eat(), Type.BIG_INT);
		if (word.equals("real")) return new LeekType(mTokens.eat(), Type.REAL);
		if (word.equals("string")) return new LeekType(mTokens.eat(), Type.STRING);
		if (word.equals("Class")) return new LeekType(mTokens.eat(), Type.CLASS);
		if (word.equals("Object")) return new LeekType(mTokens.eat(), Type.OBJECT);
		if (word.equals("Array") || word.equals("Set")) {
			boolean isArray = word.equals("Array");

			var array = mTokens.eat();
			LeekType arrayOrSetType;
			if (mTokens.get().getType() == TokenType.OPERATOR && mTokens.get().getWord().equals("<")) {
				arrayOrSetType = new LeekParameterType(array, mTokens.eat());
				var value = eatType(false, true);
				Type valueType = Type.ANY;
				if (value != null) valueType = value.getType();

				if (!mTokens.get().getWord().startsWith(">")) {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.CLOSING_CHEVRON_EXPECTED));
				}
				((LeekParameterType) arrayOrSetType).close(mTokens.eat());
				arrayOrSetType.setType(isArray ? Type.array(valueType) : Type.set(valueType));
			} else {
				arrayOrSetType = new LeekType(array, isArray ? Type.ARRAY : Type.SET);
			}
			return arrayOrSetType;
		}
		if (word.equals("Map")) {
			var map = mTokens.eat();
			Type keyType = Type.ANY, valueType = Type.ANY;
			LeekType mapType;
			if (mTokens.get().getType() == TokenType.OPERATOR && mTokens.get().getWord().equals("<")) {
				mapType = new LeekParameterType(map, mTokens.eat());
				var key = eatType(false, true);
				if (key != null) keyType = key.getType();

				if (mTokens.get().getWord().equals(",")) {
					mTokens.eat().setExpression(mapType);
				} else {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.COMMA_EXPECTED));
				}
				var value = eatType(false, true);
				if (value != null) valueType = value.getType();
				else {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.TYPE_EXPECTED));
				}

				if (!mTokens.get().getWord().startsWith(">")) addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.CLOSING_CHEVRON_EXPECTED));
				((LeekParameterType) mapType).close(mTokens.eat());

				mapType.setType(Type.map(keyType, valueType));
			} else {
				mapType = new LeekType(map, Type.MAP);
			}
			return mapType;
		}
		if (word.equals("Function")) {
			var token = mTokens.eat();
			LeekType functionType;
			if (mTokens.get().getType() == TokenType.OPERATOR && mTokens.get().getWord().equals("<")) {
				functionType = new LeekParameterType(token, mTokens.eat());

				var value = eatType(false, false);
				var function = new FunctionType(Type.ANY);
				if (value != null) function.add_argument(value.getType(), false);

				while (mTokens.get().getWord().equals(",")) {

					if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

					mTokens.eat().setExpression(functionType);

					var parameter = eatType(false, true);
					if (parameter != null) {
						function.add_argument(parameter.getType(), false);
					} else {
						addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.TYPE_EXPECTED));
					}
				}

				if (mTokens.get().getType() == TokenType.ARROW) {
					mTokens.eat().setExpression(functionType);

					var type = eatType(false, true);
					if (type == null) {
						addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.TYPE_EXPECTED));
					} else {
						function.setReturnType(type.getType());
					}
				}

				if (!mTokens.get().getWord().startsWith(">")) {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.CLOSING_CHEVRON_EXPECTED));
				}
				((LeekParameterType) functionType).close(mTokens.eat());
				functionType.setType(function);
			} else {
				functionType = new LeekType(token, Type.FUNCTION);
			}
			return functionType;
		}

		var clazz = mMain.getDefinedClass(word);
		if (clazz != null) {
			return new LeekType(mTokens.eat(), clazz.getType());
		}

		if (mandatory) {
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.TYPE_EXPECTED));
		}

		return null;
	}

	private void forBlock() throws LeekCompilerException {
		var token = mTokens.eat();
		// Bloc de type for(i=0;i<5;i++) ou encore for(element in tableau)
		// On peut déclarer une variable pendant l'instruction d'initialisation
		if (mTokens.eat().getType() != TokenType.PAR_LEFT) {
			throw new LeekCompilerException(mTokens.get(), Error.OPENING_PARENTHESIS_EXPECTED);
		}

		boolean isDeclaration = false;
		AbstractLeekBlock forBlock = null;

		// Là on doit déterminer si y'a déclaration de variable
		var type = eatType(true, false);
		if (mTokens.get().getWord().equals("var")) { // Il y a déclaration
			isDeclaration = true;
			mTokens.skip();
		} else if (type != null) {
			isDeclaration = true;
		}
		// Référence ?
		boolean reference1 = false;
		if (mTokens.get().getWord().equals("@")) {
			reference1 = true;
			if (getVersion() >= 2) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.WARNING, Error.REFERENCE_DEPRECATED));
			}
			mTokens.skip();
		}
		// On récupère ensuite le nom de la variable
		if (mTokens.get().getType() != TokenType.STRING) throw new LeekCompilerException(mTokens.get(), Error.VARIABLE_NAME_EXPECTED);
		Token varName = mTokens.eat();

		// Maintenant on va savoir si on a affaire à un for (i in array) ou à un for(i=0;i<...
		if (mTokens.get().getWord().equals(":")) { // C'est un for (key:value in array)
			mTokens.skip();
			boolean isValueDeclaration = false;

			var valueType = eatType(true, false);
			if (mTokens.get().getWord().equals("var")) { // Il y a déclaration de la valeur
				isValueDeclaration = true;
				mTokens.skip();
			} else if (valueType != null) {
				isValueDeclaration = true;
			}
			// Référence ?
			boolean reference2 = false;
			if (mTokens.get().getWord().equals("@")) {
				reference2 = true;
				if (getVersion() >= 2) {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.WARNING, Error.REFERENCE_DEPRECATED));
				}
				mTokens.skip();
			}
			// On récupère ensuite le nom de la variable accueillant la valeur
			if (mTokens.get().getType() != TokenType.STRING) throw new LeekCompilerException(mTokens.get(), Error.VARIABLE_NAME_EXPECTED);
			Token valueVarName = mTokens.eat();

			if (!mTokens.eat().getWord().equals("in")) throw new LeekCompilerException(mTokens.get(), Error.KEYWORD_IN_EXPECTED);

			// On déclare notre bloc foreach et on entre dedans
			ForeachKeyBlock block = new ForeachKeyBlock(mCurentBlock, mMain, isDeclaration, isValueDeclaration, token, reference1, reference2);
			mCurentBlock.addInstruction(this, block);
			mCurentBlock = block;

			// On lit le array (ou liste de valeurs)
			var array = readExpression();
			block.setArray(array);
			block.setKeyIterator(this, varName, isDeclaration, type);
			block.setValueIterator(this, valueVarName, isValueDeclaration, valueType);

			forBlock = block;
		} else if (mTokens.get().getWord().equals("in")) { // C'est un for (i in array)
			mTokens.skip();

			ForeachBlock block = new ForeachBlock(mCurentBlock, mMain, isDeclaration, token, reference1);
			mCurentBlock.addInstruction(this, block);
			mCurentBlock = block;

			// do not add for block argument variable if user is in another AI file
			if (userDefinitionsContext != null && userDefinitionsContext.userCursorInSameFile(this.getAI())) {
				if (userDefinitionsContext.debug) {
					System.out.println("[forBlock] Creating foreach block for variable " + varName.getWord());
				}
				// int line, int col, String fileName, String folderName, String name, String
				// type
				UserVariableDeclaration forParameterVariable = new UserVariableDeclaration(
						varName.getLocation().getStartLine(),
						varName.getLocation().getStartColumn(),
						varName.getLocation().getFile().getName(),
						varName.getLocation().getFile().getFolder().getName(),
						varName.getWord(),
						type == null ? "any" : type.getType().name);

				// userDefinitionsContext.result.variables.add(varDecl);
				forParameterVariable.setParentBlockRef(block);
				userDefinitionsContext.addVariable("forBlock", forParameterVariable);
			}

			// On lit le array (ou liste de valeurs)
			var array = readExpression();
			block.setArray(array);
			block.setIterator(this, varName, type == null ? Type.ANY : type.getType());

			forBlock = block;
		} else if (mTokens.get().getWord().equals("=")) { // C'est un for (i=0;i<1;i++)
			mTokens.skip();

			ForBlock block = new ForBlock(mCurentBlock, mMain, token);
			mCurentBlock.addInstruction(this, block);
			mCurentBlock = block;

			// On récupère la valeur de base du compteur
			var initValue = readExpression();
			if (mTokens.eat().getType() != TokenType.END_INSTRUCTION) {
				// errors.add(new AnalyzeError(mTokens.getWord(), AnalyzeErrorLevel.ERROR, Error.END_OF_INSTRUCTION_EXPECTED));
				throw new LeekCompilerException(mTokens.get(), Error.END_OF_INSTRUCTION_EXPECTED);
				// return;
			}
			var condition = readExpression();
			if (mTokens.eat().getType() != TokenType.END_INSTRUCTION) {
				// errors.add(new AnalyzeError(mTokens.getWord(), AnalyzeErrorLevel.ERROR, Error.END_OF_INSTRUCTION_EXPECTED));
				throw new LeekCompilerException(mTokens.get(), Error.END_OF_INSTRUCTION_EXPECTED);
				// return;
			}
			// if (mTokens.getWord().getType() == TokenType.END_INSTRUCTION) {
			// 	mTokens.skipWord();
			// }
			var incrementation = readExpression();

			// Attention si l'incrémentation n'est pas une expression Java fait la gueule !
			if (incrementation != null && (incrementation instanceof LeekVariable || (incrementation instanceof LeekExpression && ((LeekExpression) incrementation).getOperator() == -1))) {
				throw new LeekCompilerException(mTokens.get(), Error.UNCOMPLETE_EXPRESSION);
			}

			block.setInitialisation(this, varName, initValue, isDeclaration, block.hasGlobal(varName.getWord()), type == null ? Type.ANY : type.getType());
			block.setCondition(condition);
			block.setIncrementation(incrementation);

			forBlock = block;
		} else throw new LeekCompilerException(mTokens.get(), Error.KEYWORD_UNEXPECTED);

		// On vérifie la parenthèse fermante
		if (mTokens.eat().getType() != TokenType.PAR_RIGHT) {
			throw new LeekCompilerException(mTokens.get(), Error.CLOSING_PARENTHESIS_EXPECTED);
		}
		// On regarde s'il y a des accolades
		if (mTokens.get().getType() == TokenType.ACCOLADE_LEFT) {
			mTokens.skip();
		} else forBlock.noAccolade();
	}

	private void whileBlock() throws LeekCompilerException {
		var token = mTokens.eat();
		if (mTokens.eat().getType() != TokenType.PAR_LEFT) {
			throw new LeekCompilerException(mTokens.get(), Error.OPENING_PARENTHESIS_EXPECTED);
		}
		var exp = readExpression();
		if (mTokens.eat().getType() != TokenType.PAR_RIGHT) {
			throw new LeekCompilerException(mTokens.get(), Error.CLOSING_PARENTHESIS_EXPECTED);
		}
		WhileBlock bloc = new WhileBlock(mCurentBlock, mMain, token);
		bloc.setCondition(exp);
		if (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.ACCOLADE_LEFT) {
			mTokens.skip();
		} else if (mTokens.get().getType() == TokenType.END_INSTRUCTION) {
			mTokens.skip();
			bloc.addInstruction(this, new BlankInstruction());
			bloc.noAccolade();
		} else {
			bloc.noAccolade();
		}
		mCurentBlock.addInstruction(this, bloc);
		mCurentBlock = bloc;
	}

	private void doWhileBlock() throws LeekCompilerException {
		var token = mTokens.eat();
		DoWhileBlock bloc = new DoWhileBlock(mCurentBlock, mMain, token);
		if (mTokens.get().getType() == TokenType.ACCOLADE_LEFT) {
			mTokens.skip();
		} else bloc.noAccolade();
		mCurentBlock.addInstruction(this, bloc);
		mCurentBlock = bloc;
	}

	private void dowhileendBlock(DoWhileBlock bloc) throws LeekCompilerException {
		if (!mTokens.eat().getWord().equals("while")) throw new LeekCompilerException(mTokens.get(), Error.WHILE_EXPECTED_AFTER_DO);
		if (mTokens.eat().getType() != TokenType.PAR_LEFT) {
			throw new LeekCompilerException(mTokens.get(), Error.OPENING_PARENTHESIS_EXPECTED);
		}
		bloc.setCondition(readExpression());
		if (mTokens.eat().getType() != TokenType.PAR_RIGHT) {
			throw new LeekCompilerException(mTokens.get(), Error.CLOSING_PARENTHESIS_EXPECTED);
		}
		// if (mTokens.getWord().getType() != TokenType.END_INSTRUCTION)
		// 	throw new LeekCompilerException(mTokens.lastWord(), Error.END_OF_INSTRUCTION_EXPECTED);
	}

	private void elseBlock() throws LeekCompilerException {
		var token = mTokens.eat();
		// On vérifie qu'on est bien associé à un bloc conditionnel
		ConditionalBloc last = mCurentBlock.getLastOpenedConditionalBlock();
		if (last == null || last.getCondition() == null) {
			throw new LeekCompilerException(mTokens.get(), Error.NO_IF_BLOCK);
		}
		ConditionalBloc bloc = new ConditionalBloc(mCurentBlock, mMain, token);
		bloc.setParentCondition(last);
		if (mTokens.get().getWord().equals("if")) {
			// On veut un elseif
			mTokens.skip();
			if (mTokens.eat().getType() != TokenType.PAR_LEFT) {
				throw new LeekCompilerException(mTokens.get(), Error.OPENING_PARENTHESIS_EXPECTED);
			}
			var exp = readExpression();
			if (mTokens.eat().getType() != TokenType.PAR_RIGHT) {
				throw new LeekCompilerException(mTokens.get(), Error.CLOSING_PARENTHESIS_EXPECTED);
			}
			bloc.setCondition(exp);
		}

		if (mTokens.get().getType() == TokenType.ACCOLADE_LEFT) {
			mTokens.skip();
		} else bloc.noAccolade();
		last.getParent().addInstruction(this, bloc);
		mCurentBlock = bloc;
	}

	private void ifBlock() throws LeekCompilerException {
		var token = mTokens.eat();
		if (mTokens.eat().getType() != TokenType.PAR_LEFT) {
			throw new LeekCompilerException(mTokens.get(), Error.OPENING_PARENTHESIS_EXPECTED);
		}
		var exp = readExpression();
		if (mTokens.eat().getType() != TokenType.PAR_RIGHT) {
			throw new LeekCompilerException(mTokens.get(), Error.CLOSING_PARENTHESIS_EXPECTED);
		}
		var bloc = new ConditionalBloc(mCurentBlock, mMain, token);
		bloc.setCondition(exp);
		if (mTokens.get().getType() == TokenType.ACCOLADE_LEFT) {
			mTokens.skip();
		} else if (mTokens.get().getType() == TokenType.END_INSTRUCTION) {
			mTokens.skip();
			bloc.addInstruction(this, new BlankInstruction());
			bloc.noAccolade();
		} else bloc.noAccolade();
		mCurentBlock.addInstruction(this, bloc);
		mCurentBlock = bloc;
	}

	private void globalDeclaration() throws LeekCompilerException {
		// Il y a au moins une premiere variable
		Token token = mTokens.eat();

		var type = eatType(true, false);

		Token word = mTokens.eat();
		if (!(mCurentBlock instanceof MainLeekBlock)) {
			throw new LeekCompilerException(word, Error.GLOBAL_ONLY_IN_MAIN_BLOCK);
		}
		if (word.getType() != TokenType.STRING) {
			throw new LeekCompilerException(word, Error.VAR_NAME_EXPECTED_AFTER_GLOBAL);
		}
		var variable = new LeekGlobalDeclarationInstruction(token, word, type);
		// On regarde si une valeur est assignée
		if (mTokens.get().getWord().equals("=")) {
			mTokens.skip();
			// Si oui on récupère la valeur en question
			variable.setValue(readExpression(true));
		}

		if (userDefinitionsContext != null) {
			// only add variables that are declared in the same file
			if (userDefinitionsContext.debug) {
				System.out.println("[globalDeclaration] Found user global definition for variable: "
						+ word.getWord()
						+ " with type: " + (type != null ? type.getType().name : "any"));
			}
			if (mCurentBlock instanceof ForBlock) {

				// check if user cursor is inside the for block
				ForBlock forBlock = (ForBlock) mCurentBlock;
				Location loc = forBlock.getLocation();
				int endBlock = forBlock.getEndBlock();
				int startLine = loc.getStartLine();
				int endColumn = loc.getEndColumn();
				int endLine = loc.getEndLine();
				int cursorLine = userDefinitionsContext.line;

				if (userDefinitionsContext.debug) {
					System.out.println(
							"[globalDeclaration] User global (SKIP FOR NOW): " + word.getWord()
									+ " is in a scoped bllock "
									+ " user cursor line: "
									+ userDefinitionsContext.line + " user cursor column: "
									+ userDefinitionsContext.column + " scoped bllock startLine: " + startLine
									+ " endLine: " + endLine + " end column: " + endColumn + " block end: "
									+ endBlock);
				}

				if (cursorLine >= startLine && cursorLine <= endLine) {
					if (userDefinitionsContext.debug) {
						System.out.println("[globalDeclaration] User global: " + word.getWord()
								+ " is inside the scoped bllock at line " + cursorLine);
					}
					// do not add the variable to user definitions if cursor is inside the for block
					return;
				}

			}
			UserVariableDeclaration userVarDef = new UserVariableDeclaration(word.getLocation().getStartLine(),
					word.getLocation().getStartColumn(), word.getLocation().getFile().getName(),
					word.getLocation().getFile().getFolder().getName(),
					word.getWord(),
					type != null ? type.getType().name : Type.ANY.name);
			userDefinitionsContext.result.variables.add(userVarDef);
		}

		// On ajoute la variable
		mMain.addGlobalDeclaration(variable);
		mCurentBlock.addInstruction(this, variable);
		while (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.VIRG) {

			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

			// On regarde si y'en a d'autres
			mTokens.skip();// On passe la virgule
			word = mTokens.eat();
			if (word.getType() != TokenType.STRING) throw new LeekCompilerException(word, Error.VAR_NAME_EXPECTED);
			variable = new LeekGlobalDeclarationInstruction(token, word, type);
			// On regarde si une valeur est assignée
			if (mTokens.get().getWord().equals("=")) {
				mTokens.skip();
				// Si oui on récupère la valeur en question
				variable.setValue(readExpression(true));
			}
			// On ajoute la variable
			mMain.addGlobalDeclaration(variable);
			mCurentBlock.addInstruction(this, variable);
		}
		// word = mTokens.readWord();
		// if (word.getType() != TokenType.END_INSTRUCTION)
		// throw new LeekCompilerException(word, Error.END_OF_INSTRUCTION_EXPECTED);
		if (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.END_INSTRUCTION) mTokens.skip();
	}

	private void variableDeclaration(LeekType type) throws LeekCompilerException {
		// Il y a au moins une premiere variable
		Token word = mTokens.eat();
		if (word.getType() != TokenType.STRING) {
			throw new LeekCompilerException(word, Error.VAR_NAME_EXPECTED);
		}

		if (getVersion() >= 3 && isKeyword(word)) {
			addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.VARIABLE_NAME_UNAVAILABLE));
		}
		LeekVariableDeclarationInstruction variable = new LeekVariableDeclarationInstruction(this, word,
				getCurrentFunction(), type);

		if (userDefinitionsContext != null) {
			if (userDefinitionsContext.debug) {
				System.out.println("[variableDeclaration] 1 Created with leekType: " + type + " for variable: "
						+ word.getWord());
			}

			AbstractLeekBlock parentBlockRef = null;

			// only add variables that are declared in the same file
			if (userDefinitionsContext.aiFile == this.getAI()) {
				if (userDefinitionsContext.debug) {
					System.out.println("[variableDeclaration] Found user variable definition for variable: "
							+ word.getWord());
				}

				boolean userCursorIsBeforeOrEqualWordLine = userDefinitionsContext
						.userCursorLineBeforeOrEqual(word.getLocation().getStartLine());

				// check if variable is declared inside a for block
				if (mCurentBlock instanceof ForBlock || mCurentBlock instanceof ForeachBlock
						|| mCurentBlock instanceof ForeachKeyBlock || mCurentBlock instanceof WhileBlock
						|| mCurentBlock instanceof ClassMethodBlock || mCurentBlock instanceof ConditionalBloc) {
					// check if user cursor is inside the for block
					parentBlockRef = mCurentBlock;
					Location parentBlockLocation = parentBlockRef.getLocation();
					int endBlock = parentBlockRef.getEndBlock();
					int parentBlockStartLine = parentBlockLocation.getStartLine();
					int parentBlockEndColumn = parentBlockLocation.getEndColumn();
					int parentBlockEndLine = parentBlockLocation.getEndLine();
					int cursorLine = userDefinitionsContext.line;

					if (userDefinitionsContext.debug) {
						System.out.println(
								"[variableDeclaration] " + userDefinitionsContext.userCursorLocationToString());
						System.out.println(
								"[variableDeclaration] ###### Scoped User variable: " + word.getWord()
										+ " parentBlockStartLine: "
										+ parentBlockStartLine
										+ " parentBlockEndLine: " + parentBlockEndLine + " parentBlockEndColumn: "
										+ parentBlockEndColumn
										+ " block end: "
										+ endBlock);
					}

					if (cursorLine >= parentBlockStartLine && cursorLine <= parentBlockEndLine) {
						if (userDefinitionsContext.debug) {
							System.out
									.println("[variableDeclaration] >>>RETURN??<<< User variable: " + word.getWord()
											+ " is inside the scoped Block at line " + cursorLine);
						}
						// do not add the variable to user definitions if cursor is inside the for block
						return;
					}

				}

				if (userCursorIsBeforeOrEqualWordLine) {
					if (userDefinitionsContext.debug) {
						System.out.println("[variableDeclaration] User variable: " + word.getWord()
								+ " is BeforeOrEqual the user cursor line: " + userDefinitionsContext.line);
					}
				}

				if (!userCursorIsBeforeOrEqualWordLine) {
					UserVariableDeclaration userVarDef = new UserVariableDeclaration(word.getLocation().getStartLine(),
							word.getLocation().getStartColumn(), word.getLocation().getFile().getName(),
							word.getLocation().getFile().getFolder().getName(),
							word.getWord(),
							type != null ? type.getType().name : Type.ANY.name);
					userVarDef.setParentBlockRef(parentBlockRef);
					userDefinitionsContext.addVariable("[variableDeclaration]", userVarDef);
				} else {
					if (userDefinitionsContext.debug) {
						System.out.println("[variableDeclaration] User variable (SKIP): " + word.getWord()
								+ " is declared after the user cursor line: " + userDefinitionsContext.line);
					}
				}

			} else {
				if (userDefinitionsContext.debug) {
					System.out.println("[variableDeclaration] User variable (SKIP): " + word.getWord()
							+ " is in another file.");
				}
			}
		}

		// On regarde si une valeur est assignée
		if (mTokens.hasMoreTokens() && mTokens.get().getWord().equals("=")) {
			mTokens.skip();

			// Arrow function?
			int p = mTokens.getOffsetToNextClosingParenthesis();
			int a = mTokens.getOffsetToNextArrow();
			boolean isArrowFunction = a != -1 && (a < p || p == -1);

			// Si oui on récupère la valeur en question
			variable.setValue(readExpression(!isArrowFunction));
		}
		mCurentBlock.addInstruction(this, variable);

		while (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.VIRG) {

			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

			// On regarde si y'en a d'autres
			mTokens.skip();// On passe la virgule
			word = mTokens.eat();
			if (word.getType() != TokenType.STRING) throw new LeekCompilerException(word, Error.VAR_NAME_EXPECTED);
			if (getVersion() >= 3 && isKeyword(word)) {
				addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.VARIABLE_NAME_UNAVAILABLE));
			}
			variable = new LeekVariableDeclarationInstruction(this, word, getCurrentFunction(), type);
			// On regarde si une valeur est assignée
			if (mTokens.get().getWord().equals("=")) {
				mTokens.skip();
				// Si oui on récupère la valeur en question
				variable.setValue(readExpression(true));
			}
			// On ajoute la variable
			mCurentBlock.addInstruction(this, variable);
		}
		if (mTokens.hasMoreTokens() && mTokens.get().getType() == TokenType.END_INSTRUCTION) {
			mTokens.skip();
		}
	}

	public void classDeclaration() throws LeekCompilerException {
		// Read class name
		Token word = mTokens.eat();
		if (word.getType() != TokenType.STRING) {
			throw new LeekCompilerException(word, Error.VAR_NAME_EXPECTED);
		}
		if (isKeyword(word)) {
			addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.VARIABLE_NAME_UNAVAILABLE, new String[] { word.getWord() }));
		}
		ClassDeclarationInstruction classDeclaration = mMain.getDefinedClass(word.getWord());
		assert classDeclaration != null : "Class " + word.getWord() + " not declared (" + mMain.getDefinedClasses().size() + " classes)";
		mMain.addClassList(classDeclaration);
		mCurrentClass = classDeclaration;

		if (mTokens.get().getType() == TokenType.EXTENDS) {
			mTokens.skip();
			Token parent = mTokens.eat();
			classDeclaration.setParent(parent);

			if (this.userDefinitionsContext != null) {
				if (this.userDefinitionsContext.debug) {
					System.out.println("[classDeclaration] Class " + word.getWord()
							+ " extends " + parent.getWord());
				}
				UserClassDefinition parentClassDef = this.userDefinitionsContext
						.getClassDefinition(parent.getWord());
				UserClassDefinition currentClassDef = this.userDefinitionsContext
						.getClassDefinition(word.getWord());

				if (parentClassDef != null && currentClassDef != null) {
					if (this.userDefinitionsContext.debug) {
						System.out.println("[classDeclaration] Found parent class definition for user class: "
								+ word.getWord() + " -> " + parent.getWord());
					}
					currentClassDef.setParentClassName(parentClassDef.name);
					// currentClassDef.set
				} else if (this.userDefinitionsContext.debug) {
					System.out.println("[classDeclaration] No parent class definition found for user class: "
							+ word.getWord());
				}
			}
		}
		if (mTokens.get().getType() != TokenType.ACCOLADE_LEFT) {
			throw new LeekCompilerException(mTokens.get(), Error.OPENING_CURLY_BRACKET_EXPECTED);
		}
		mTokens.skip();

		UserVariableDeclaration tmpThisVar = null;

		// we are entering the class scope
		if (userDefinitionsContext != null) {
			if (userDefinitionsContext.aiFile == this.getAI()) {
				// check if user cursor is inside the class block
				Location insideClassLocation = mTokens.get().getLocation();
				int insideClassLocationStartLine = insideClassLocation.getStartLine();

				if (!userDefinitionsContext.userCursorLineBefore(insideClassLocationStartLine)) {
					Location classLocation = classDeclaration.getLocation();
					int classStartLine = classLocation.getStartLine();
					int classStartColumn = classLocation.getStartColumn();

					// temporary add "this" variable to user definitions
					tmpThisVar = new UserVariableDeclaration(
							classStartLine,
							classStartColumn,
							insideClassLocation.getFile().getName(),
							insideClassLocation.getFile().getFolder().getName(),
							"this",
							classDeclaration.getType().name);

					userDefinitionsContext.addVariable("[classDeclaration enter]", tmpThisVar);
				}
			}
		}

		while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.ACCOLADE_RIGHT) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
			word = mTokens.get();
			switch (word.getWord()) {
				case "public":
				case "private":
				case "protected":
				{
					AccessLevel level = AccessLevel.fromString(word.getWord());
					mTokens.skip();
					classAccessLevelMember(classDeclaration, level);
					break;
				}
				case "static":
				{
					mTokens.skip();
					classStaticMember(classDeclaration, AccessLevel.PUBLIC);
					break;
				}
				case "final":
				{
					mTokens.skip();
					endClassMember(classDeclaration, AccessLevel.PUBLIC, false, true);
					break;
				}
				case "constructor":
				{
					mTokens.skip();
					classConstructor(classDeclaration, AccessLevel.PUBLIC, word);
					break;
				}
				default:
				{
					endClassMember(classDeclaration, AccessLevel.PUBLIC, false, false);
				}
			}
		}
		if (mTokens.get().getType() != TokenType.ACCOLADE_RIGHT) {
			throw new LeekCompilerException(mTokens.get(), Error.END_OF_CLASS_EXPECTED);
		}

		// we are exiting the class scope, check if "this" variable needs to be removed
		if (userDefinitionsContext != null && userDefinitionsContext.aiFile == this.getAI()) {
			Location endTokenLocation = mTokens.get().getLocation();
			int endTokenLine = endTokenLocation.getStartLine();

			if (userDefinitionsContext.userCursorLineAfterOrEqual(endTokenLine)) {
				// remove "this" variable from user definitions
				userDefinitionsContext.removeVariable("[classDeclaration exit]", tmpThisVar);
				if (userDefinitionsContext.debug) {
					System.out.println(
							"[classDeclaration] Removing 'this' variable as user cursor is after class end at line "
									+ endTokenLine);
				}
			} else {
				if (userDefinitionsContext.debug) {
					System.out.println(
							"[classDeclaration] Keeping 'this' variable as user cursor is before class end at line "
									+ endTokenLine);
				}
			}
		}

		mTokens.skip();
		mCurrentClass = null;
	}

	public void classStaticMember(ClassDeclarationInstruction classDeclaration, AccessLevel accessLevel) throws LeekCompilerException {
		Token token = mTokens.get();
		switch (token.getWord()) {
			case "final":
				mTokens.skip();
				endClassMember(classDeclaration, accessLevel, true, true);
				return;
		}
		endClassMember(classDeclaration, accessLevel, true, false);
	}

	public void classAccessLevelMember(ClassDeclarationInstruction classDeclaration, AccessLevel accessLevel) throws LeekCompilerException {
		Token token = mTokens.get();
		switch (token.getWord()) {
			case "constructor":
				mTokens.skip();
				classConstructor(classDeclaration, accessLevel, token);
				return;
			case "static":
				mTokens.skip();
				classStaticMember(classDeclaration, accessLevel);
				return;
			case "final":
				mTokens.skip();
				endClassMember(classDeclaration, accessLevel, false, true);
				return;
		}
		endClassMember(classDeclaration, accessLevel, false, false);
	}

	public void endClassMember(ClassDeclarationInstruction classDeclaration, AccessLevel accessLevel, boolean isStatic, boolean isFinal) throws LeekCompilerException {

		var isStringMethod = mTokens.get().getWord().equals("string") && mTokens.get(1).getType() == TokenType.PAR_LEFT;

		var typeExpression = isStringMethod ? null : eatType(false, false);

		Token name = mTokens.eat();
		if (name.getType() != TokenType.STRING) {
			addError(new AnalyzeError(name, AnalyzeErrorLevel.ERROR, Error.VARIABLE_NAME_EXPECTED, new String[] { name.getWord() }));
			return;
		}

		if (name.getWord().equals("super") || name.getWord().equals("class")) {
			addError(new AnalyzeError(name, AnalyzeErrorLevel.ERROR, Error.VARIABLE_NAME_UNAVAILABLE, new String[] { name.getWord() }));
		} else if (isKeyword(name)) {
			addError(new AnalyzeError(name, AnalyzeErrorLevel.ERROR, Error.VARIABLE_NAME_UNAVAILABLE, new String[] { name.getWord() }));
		}

		// Field
		Expression expr = null;
		if (mTokens.get().getType() == TokenType.OPERATOR && mTokens.get().getWord().equals("=")) {
			mTokens.skip();
			expr = readExpression();
		} else if (mTokens.get().getType() == TokenType.PAR_LEFT) {
			// Méthode
			ClassMethodBlock method = classMethod(classDeclaration, name, false, isStatic,
					typeExpression == null ? Type.ANY : typeExpression.getType());

			if (this.userDefinitionsContext != null) {
				UserClassDefinition classDef = this.userDefinitionsContext
						.getClassDefinition(classDeclaration.getName());

				if (classDef != null) {
					// find corresponding method definition
					UserClassMethodDefinition methodDef = classDef.getMethodByName(name.getWord());

					if (methodDef != null) {
						methodDef.setStatic("endClassMember", this.userDefinitionsContext.debug, isStatic);
						methodDef.setLevel("endClassMember", this.userDefinitionsContext.debug, accessLevel.toString());
					} else if (this.userDefinitionsContext.debug) {
						System.out.println("[endClassMember] Warning: method definition not found for method "
								+ name.getWord() + " in class " + classDeclaration.getName());
					}
				} else if (this.userDefinitionsContext.debug) {
					System.out.println("[endClassMember] Warning: class definition is null for class "
							+ classDeclaration.getName());
				}
			}

			if (isStatic) {
				classDeclaration.addStaticMethod(this, name, method, accessLevel);
			} else {
				classDeclaration.addMethod(this, name, method, accessLevel);
			}
			if (mTokens.get().getType() == TokenType.END_INSTRUCTION) mTokens.skip();
			return;
		}

		if (isStatic) {
			// System.out.println(classDeclaration);
			assert classDeclaration != null;
			classDeclaration.addStaticField(this, name, expr, accessLevel, isFinal, typeExpression != null ? typeExpression.getType() : Type.ANY);
		} else {
			classDeclaration.addField(this, name, expr, accessLevel, isFinal, typeExpression != null ? typeExpression.getType() : Type.ANY);
		}

		if (mTokens.get().getType() == TokenType.END_INSTRUCTION) mTokens.skip();
	}

	public void classConstructor(ClassDeclarationInstruction classDeclaration, AccessLevel accessLevel, Token token) throws LeekCompilerException {
		ClassMethodBlock constructor = classMethod(classDeclaration, token, true, false, Type.VOID);
		classDeclaration.addConstructor(this, constructor, accessLevel);

		ArrayList<LeekVariableDeclarationInstruction> parameters = constructor.getParametersDeclarations();

		if (this.userDefinitionsContext != null) {
			classDeclaration.checkUserClassDefinitions("classConstructor", this);
			UserClassDefinition classDefinition = this.userDefinitionsContext
					.getClassDefinition(classDeclaration.getName());

			if (classDeclaration != null) {
				int col = constructor.getLocation().getStartColumn();
				int line = constructor.getLocation().getStartLine();
				String fileName = constructor.getLocation().getFile().getName();
				String folderName = constructor.getLocation().getFile().getFolder().getName();

				UserClassMethodDefinition constructorDefinition = new UserClassMethodDefinition(line, col, fileName,
						folderName, token.getWord(),
						"void");
				constructorDefinition.setLevel("classConstructor", this.userDefinitionsContext.debug,
						accessLevel.toString());

				for (var param : parameters) {
					constructorDefinition.addArgument("[classConstructor]",
							this.userDefinitionsContext.debug,
							new UserArgumentDefinition(param.getName(),
									param.getType() != null ? param.getType().toString() : "ANY"));
				}

				this.userDefinitionsContext.addClassConstructor("classConstructor", classDefinition,
						constructorDefinition);

				if (this.userDefinitionsContext.debug) {
					for (var param : parameters) {
						System.out.println("[classConstructor] Parameter: " + param.getName()
								+ " Type: " + param.getType().toString());
					}
				}
				// UserClassMethodDefinition methoconstructorDefinition = null;
			}
		}
	}

	public ClassMethodBlock classMethod(ClassDeclarationInstruction classDeclaration, Token token, boolean isConstructor, boolean isStatic, Type returnType) throws LeekCompilerException {

		ClassMethodBlock method = new ClassMethodBlock(classDeclaration, isConstructor, isStatic, mCurentBlock, mMain, token, returnType);

		Token word = mTokens.eat();
		if (word.getType() != TokenType.PAR_LEFT) {
			throw new LeekCompilerException(word, Error.OPENING_PARENTHESIS_EXPECTED);
		}

		UserClassMethodDefinition methodDefinition = null;
		UserClassDefinition classDefinition = null;

		if (this.userDefinitionsContext != null) {
			if (this.userDefinitionsContext.debug) {
				// print all word infos
				System.out.println("[classMethod] token: " + token.getWord()
						+ " Type: " + token.getType());
			}
			// when class has never been instanced in the code, the execution will go here
			// first
			classDeclaration.checkUserClassDefinitions("classMethod", this);

			if (this.userDefinitionsContext.debug) {
				System.out.println("[classMethod] Created method definition for method "
						+ (isConstructor ? "constructor" : token.getWord())
						+ " in class " + classDeclaration.getName());
			}

			// constructors are added in classConstructor method
			if (!isConstructor) {
				int col = method.getLocation().getStartColumn();
				int line = method.getLocation().getStartLine();
				String fileName = method.getLocation().getFile().getName();
				String folderName = method.getLocation().getFile().getFolder().getName();

				methodDefinition = new UserClassMethodDefinition(line, col, fileName, folderName,
						token.getWord(), returnType.toString());

				classDefinition = this.userDefinitionsContext
						.getClassDefinition(classDeclaration.getName());

				this.userDefinitionsContext.addClassMethod("[classMethod]", classDefinition, methodDefinition);
			}
		}

		int param_count = 0;
		while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.PAR_RIGHT) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
			if (mTokens.get().getType() == TokenType.OPERATOR && mTokens.get().getWord().equals("@")) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.WARNING, Error.REFERENCE_DEPRECATED));
				mTokens.skip();
			}
			var type = eatType(false, false);

			if (mTokens.get().getType() != TokenType.STRING) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARAMETER_NAME_EXPECTED));
			}
			var param = mTokens.eat();
			Token equal = null;
			Expression defaultValue = null;

			// Default param
			if (mTokens.get().getWord().equals("=")) {
				equal = mTokens.eat();
				defaultValue = readExpression(true);
			}

			method.addParameter(this, param, equal, type == null ? Type.ANY : type.getType(), defaultValue);

			// the ref doesn't match when the method ends, why?
			if (this.userDefinitionsContext != null) {
				// register param as global variable until we check if it's out of scope
				Location paramLocation = param.getLocation();

				if (this.userDefinitionsContext.debug) {
					System.out.println("[classMethod] Created parameter variable for parameter "
							+ param.getWord()
							+ " paramLocation: " + paramLocation.getStartLine() + ":" + paramLocation.getStartColumn());
				}

				if (this.userDefinitionsContext.userCursorLineAfter(paramLocation.getStartLine())
						&& this.userDefinitionsContext.userCursorInSameFile(paramLocation
								.getFile())) {
					UserVariableDeclaration methodParamVariable = new UserVariableDeclaration(
							param.getLocation().getStartLine(),
							param.getLocation().getStartColumn(),
							param.getLocation().getFile().getName(),
							param.getLocation().getFile().getFolder().getName(),
							param.getWord(),
							type == null ? "ANY" : type.getType().toString());

					methodParamVariable.setParentBlockRef(method);
					this.userDefinitionsContext.addVariable("[classMethod (param)]", methodParamVariable);
				}
			}

			if (this.userDefinitionsContext != null && !isConstructor) {
				if (methodDefinition != null) {
					UserArgumentDefinition argDef = new UserArgumentDefinition(param.getWord(),
							type == null ? "ANY" : type.getType().toString());
					methodDefinition.addArgument("[classMethod]", this.userDefinitionsContext.debug, argDef);
				} else {
					System.out.println("Warning: methodDefinition is null for method "
							+ (isConstructor ? "constructor" : token.getWord()));
				}

			}

			param_count++;

			if (mTokens.get().getType() == TokenType.VIRG) {
				mTokens.skip();
			}
		}
		if (mTokens.eat().getType() != TokenType.PAR_RIGHT) {
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS));
		}
		if (classDeclaration.hasMethod(token.getWord(), param_count)) {
			throw new LeekCompilerException(mTokens.get(), Error.CONSTRUCTOR_ALREADY_EXISTS);
		}

		// On enregistre les block actuels
		AbstractLeekBlock initialBlock = mCurentBlock;
		int initialLine = mLine;
		AIFile initialAI = mAI;
		mCurentBlock = method;

		// Ouverture des accolades
		if (mTokens.eat().getType() != TokenType.ACCOLADE_LEFT) {
			throw new LeekCompilerException(mTokens.get(), Error.OPENING_CURLY_BRACKET_EXPECTED);
		}

		// Lecture du corps de la fonction
		while (mTokens.hasMoreTokens()) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
			// Fermeture des blocs ouverts
			if (mCurentBlock instanceof DoWhileBlock && !((DoWhileBlock) mCurentBlock).hasAccolade() && mCurentBlock.isFull()) {
				DoWhileBlock do_block = (DoWhileBlock) mCurentBlock;
				mCurentBlock = mCurentBlock.endInstruction();
				dowhileendBlock(do_block);
				mTokens.skip();
			} else {
				if (userDefinitionsContext != null && !isConstructor) {
					if (userDefinitionsContext.debug) {
						System.out.println("[classMethod] Current block: " + mCurentBlock.getClass().getSimpleName()
								+ " is full: " + mCurentBlock.isFull());
						Location loc = mCurentBlock.getLocation();
						System.out.println("		Block location: " + loc.getStartLine() + ":" + loc.getStartColumn()
								+ " to " + loc.getEndLine() + ":" + loc.getEndColumn());
					}
				}
				mCurentBlock = mCurentBlock.endInstruction();
			}
			if (!mTokens.hasMoreTokens())
				break;

			// On regarde si on veut fermer la fonction anonyme
			if (mTokens.get().getType() == TokenType.ACCOLADE_RIGHT && mCurentBlock == method) {
				if (userDefinitionsContext != null) {
					Location closingBracketLocation = mTokens.get().getLocation();
					int closingBracketLine = closingBracketLocation.getStartLine();
					int closingBracketColumn = closingBracketLocation.getStartColumn();

					if (userDefinitionsContext.debug && !isConstructor) {
						System.out.println("[classMethod] ACCOLADE_RIGHT: " + mTokens.get().getWord()
								+ " closingBracketLine: " + closingBracketLine + ", closingBracketColumn: "
								+ closingBracketColumn);
					}

					UserVariableDeclaration lastVar = userDefinitionsContext.getLatestDeclaredVariable();

					while (lastVar != null) {
						if (lastVar.getParentBlockRef() == method) {
							if (userDefinitionsContext.debug) {
								System.out.println("\t" + userDefinitionsContext.userCursorLocationToString());
							}
							if (userDefinitionsContext.userCursorLineAfterOrEqual(closingBracketLine)) {
								if (userDefinitionsContext.debug) {
									System.out.println("[classMethod] Removing variable: " + lastVar.name
											+ " declared at line " + lastVar.line
											+ " because user cursor is after parent method end at line "
											+ closingBracketLine);
								}
								userDefinitionsContext.removeVariable("[classMethod]", lastVar);
							}
							lastVar.clearParentBlockRef();
						} else {
							lastVar.clearParentBlockRef();
							break;
						}
						lastVar = userDefinitionsContext.getLatestDeclaredVariable();
					}
				}
				mTokens.skip();
				break; // Fermeture de la fonction anonyme
			} else {

				compileWord();
			}
		}
		// On remet le bloc initial
		mCurentBlock = initialBlock;
		mLine = initialLine;
		mAI = initialAI;
		return method;
	}

	public Expression readExpression() throws LeekCompilerException {
		return readExpression(false, false, false);
	}

	public Expression readExpression(boolean inList) throws LeekCompilerException {
		return readExpression(inList, false, false);
	}

	public Expression readExpression(boolean inList, boolean inSet) throws LeekCompilerException {
		return readExpression(inList, inSet, false);
	}

	public Expression readExpression(boolean inList, boolean inSet, boolean inInterval) throws LeekCompilerException {

		var retour = new LeekExpression();

		// Lambda
		boolean parenthesis = false;
		Token lambdaToken = null;
		LeekType type1 = null;
		var pos = mTokens.getPosition();
		if (mTokens.get().getType() == TokenType.PAR_LEFT) {
			lambdaToken = mTokens.eat();
			parenthesis = true;
		}
		type1 = eatType(true, false);
		var t1 = mTokens.get().getType();
		var t2 = mTokens.get(1).getType();
		var t3 = mTokens.get(2).getType();
		// var t4 = mTokens.get(3).getType();
		if (t1 == TokenType.ARROW // =>
				|| ((!inList || parenthesis) && t1 == TokenType.STRING && t2 == TokenType.VIRG) // x,
				//  || (t1 == TokenType.PAR_LEFT && t2 == TokenType.STRING && t3 == TokenType.VIRG) // (x,
				|| (t1 == TokenType.STRING && t2 == TokenType.ARROW) // x =>
				|| (parenthesis && t1 == TokenType.STRING && t2 == TokenType.PAR_RIGHT && t3 == TokenType.ARROW) // (x) =>
		//  || (t1 == TokenType.PAR_LEFT && t2 == TokenType.STRING && t3 == TokenType.PAR_RIGHT && t4 == TokenType.ARROW) // (x) =>
		) {
			if (!parenthesis) {
				lambdaToken = t1 == TokenType.ARROW ? mTokens.get() : mTokens.get(1); // , ou =>
			}

			var block = new AnonymousFunctionBlock(getCurrentBlock(), getMainBlock(), lambdaToken);
			AbstractLeekBlock initialBlock = mCurentBlock;
			setCurrentBlock(block);

			boolean first = true;
			while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.PAR_RIGHT && mTokens.get().getType() != TokenType.ARROW) {
				if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

				if (mTokens.get().getType() != TokenType.STRING) {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARAMETER_NAME_EXPECTED));
					break;
				}
				var type = first && type1 != null ? type1 : eatType(false, false);
				var parameter = mTokens.get();
				mTokens.skip();

				if (mTokens.get().getType() == TokenType.VIRG) {
					mTokens.skip();
				}
				block.addParameter(this, parameter, false, type);
				first = false;
			}

			boolean surroudingParenthesis = false;
			if (parenthesis) {
				if (mTokens.get().getType() == TokenType.ARROW) {
					surroudingParenthesis = true;
				} else {
					if (mTokens.get().getType() != TokenType.PAR_RIGHT) {
						addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS));
					}
					mTokens.skip();
				}
			}
			if (mTokens.get().getType() != TokenType.ARROW) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.ARROW_EXPECTED));
			}
			mTokens.skip();

			// Type de retour
			pos = mTokens.getPosition();
			var returnType = eatType(false, false);
			if (returnType != null) {
				if (mTokens.get().getWord().equals(".")) {
					mTokens.setPosition(pos); // On prend pas le type si "Type.[...]"
				} else {
					block.setReturnType(returnType.getType());
				}
			}

			// boolean surroudingCurlyBracket = false;
			if (mTokens.get().getType() == TokenType.ACCOLADE_LEFT) {
				// surroudingCurlyBracket = true;
				mTokens.skip();

				// Lecture du corps de la fonction
				while (mTokens.hasMoreTokens()) {
					if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
					// Fermeture des blocs ouverts
					if (mCurentBlock instanceof DoWhileBlock && !((DoWhileBlock) mCurentBlock).hasAccolade() && mCurentBlock.isFull()) {
						DoWhileBlock do_block = (DoWhileBlock) mCurentBlock;
						mCurentBlock = mCurentBlock.endInstruction();
						dowhileendBlock(do_block);
						mTokens.skip();
					} else mCurentBlock = mCurentBlock.endInstruction();
					if (!mTokens.hasMoreTokens()) break;

					// On regarde si on veut fermer la fonction anonyme
					if (mTokens.get().getType() == TokenType.ACCOLADE_RIGHT && mCurentBlock == block) {
						mTokens.skip();
						break; // Fermeture de la fonction anonyme
					} else compileWord();
				}
			} else {

				// Expression seule
				var body = readExpression();
				block.addInstruction(this, new LeekReturnInstruction(lambdaToken, body, false));
			}

			if (surroudingParenthesis) {
				if (mTokens.get().getType() != TokenType.PAR_RIGHT) {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS));
				}
				mTokens.skip();
			}

			setCurrentBlock(initialBlock);
			var f = new LeekAnonymousFunction(block, lambdaToken);
			retour.addExpression(f);

		} else {
			// Pas une lambda, on revient au début
			mTokens.setPosition(pos);
		}

		while (mTokens.hasMoreTokens()) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
			Token word = mTokens.get();
			if (word.getType() == TokenType.PAR_RIGHT || word.getType() == TokenType.ACCOLADE_RIGHT || word.getType() == TokenType.END_INSTRUCTION) {
				break;
			}
			if (retour.needOperator()) {
				// Si on attend un opérateur mais qu'il vient pas

				if (word.getType() == TokenType.BRACKET_LEFT && !inInterval) {

					var save = mTokens.getPosition();

					var bracket = mTokens.eat(); // On avance le curseur pour être au début de l'expression
					Token colon = null;
					Token colon2 = null;
					Expression start = null;
					Expression end = null;
					Expression stride = null;

					if (mTokens.get().getType() == TokenType.BRACKET_RIGHT) {
						// Crochet fermant direct
						addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.VALUE_EXPECTED));
					} else if (getVersion() >= 4 && mTokens.get().getWord().equals(":")) {
						colon = mTokens.eat();
						if (getVersion() >= 4 && mTokens.get().getWord().equals(":")) {
							colon2 = mTokens.eat();
							if (mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
								stride = readExpression();
							}
						} else if (mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
							end = readExpression();
							if (getVersion() >= 4 && mTokens.get().getWord().equals(":")) {
								colon2 = mTokens.eat();
								if (mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
									stride = readExpression();
								}
							}
						}
					} else if (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.PAR_RIGHT && mTokens.get().getType() != TokenType.VIRG) {
						start = readExpression();
						if (getVersion() >= 4 && mTokens.get().getWord().equals(":")) {
							colon = mTokens.eat();
							if (getVersion() >= 4 && mTokens.get().getWord().equals(":")) {
								colon2 = mTokens.eat();
								if (mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
									stride = readExpression();
								}
							} else if (mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
								end = readExpression();
								if (getVersion() >= 4 && mTokens.get().getWord().equals(":")) {
									colon2 = mTokens.eat();
									if (mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
										stride = readExpression();
									}
								}
							}
						}
					}

					if (mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
						if (inInterval) {
							mTokens.setPosition(save);
							break;
						} else {
							throw new LeekCompilerException(mTokens.get(), Error.CLOSING_SQUARE_BRACKET_EXPECTED);
						}
					}
					retour.addBracket(bracket, start, colon, end, colon2, stride, mTokens.get());

				} else if (word.getType() == TokenType.PAR_LEFT) {

					LeekFunctionCall function = new LeekFunctionCall(word);
					mTokens.skip(); // On avance le curseur pour être au début de l'expression

					while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.PAR_RIGHT) {
						if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
						function.addParameter(readExpression(true));
						if (mTokens.get().getType() == TokenType.VIRG) mTokens.skip();
					}
					if (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.PAR_RIGHT) {
						addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS));
					}
					function.setClosingParenthesis(mTokens.get());
					retour.addFunction(function);

				} else if (word.getType() == TokenType.DOT) {
					// Object access
					var dot = mTokens.eat();
					if (mTokens.get().getType() == TokenType.STRING || mTokens.get().getType() == TokenType.CLASS || mTokens.get().getType() == TokenType.SUPER) {
						var name = mTokens.get();
						retour.addObjectAccess(dot, name);
					} else {
						addError(new AnalyzeError(dot, AnalyzeErrorLevel.ERROR, Error.VALUE_EXPECTED));
						retour.addObjectAccess(dot, null);
						mTokens.unskip();
					}
				} else if (word.getType() == TokenType.IN) {

					mTokens.skip();
					retour.addOperator(Operators.IN, word);
					continue;

				} else if (word.getType() == TokenType.AS) {

					mTokens.skip();
					var type = eatType(false, true);
					if (type != null) {
						retour.addOperator(Operators.AS, word);
						retour.addExpression(type);
					}
					continue;

				} else if (word.getType() == TokenType.OPERATOR && (!word.getWord().equals(">") || !inSet)) {

					int operator = Operators.getOperator(word.getWord(), getVersion());

					// Handle ">>", ">>=", ">>>", ">>>=" operator
					if (word.getWord().equals(">")) {
						var nextToken = mTokens.get(1);
						if (nextToken.getType() == TokenType.OPERATOR && nextToken.getWord().equals(">")) {
							mTokens.skip();
							operator = Operators.SHIFT_RIGHT;
							if (mTokens.get(1).getType() == TokenType.OPERATOR && mTokens.get(1).getWord().equals(">=")) {
								mTokens.skip();
								operator = Operators.SHIFT_UNSIGNED_RIGHT_ASSIGN;
							} else if (mTokens.get(1).getType() == TokenType.OPERATOR && mTokens.get(1).getWord().equals(">")) {
								mTokens.skip();
								operator = Operators.SHIFT_UNSIGNED_RIGHT;
							}
						} else if (nextToken.getType() == TokenType.OPERATOR && nextToken.getWord().equals(">=")) {
							operator = Operators.SHIFT_RIGHT_ASSIGN;
							mTokens.skip();
						}
						retour.addOperator(operator, word);

					} else {
						if (operator == Operators.SHIFT_RIGHT) {
							var nextToken = mTokens.get(1);
							if (nextToken.getType() == TokenType.OPERATOR && nextToken.getWord().equals(">")) {
								operator = Operators.SHIFT_UNSIGNED_RIGHT;
								mTokens.skip();
							} else if (nextToken.getType() == TokenType.OPERATOR && nextToken.getWord().equals(">=")) {
								operator = Operators.SHIFT_UNSIGNED_RIGHT_ASSIGN;
								mTokens.skip();
							}
						}

						// Là c'est soit un opérateur (+ - ...) soit un suffix
						// unaire (++ -- ) sinon on sort de l'expression
						if (Operators.isUnaryPrefix(operator)) break;
						if (operator == Operators.DOUBLE_POINT && !retour.hasTernaire()) break;

						if (Operators.isUnarySuffix(operator)) retour.addUnarySuffix(operator, word);
						else retour.addOperator(operator, word);
					}
				} else if (word.getType() == TokenType.STRING) {
					if (word.getWord().equals("is")) {
						mTokens.skip();
						word = mTokens.get();
						if (word.getWord().equals("not")) {
							Token token = mTokens.eat();
							retour.addOperator(Operators.NOTEQUALS, token);
						} else {
							retour.addOperator(Operators.EQUALS, word);
						}
						continue;
					}
					break;
				} else break;
			} else {
				if (word.getType() == TokenType.NUMBER) {
					var s = word.getWord();
					if (s.contains("__")) {
						addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.MULTIPLE_NUMERIC_SEPARATORS));
					}
					var radix = s.startsWith("0x") ? 16 : s.startsWith("0b") ? 2 : 10;
					s = word.getWord().replace("_", "");
					if (radix != 10) s = s.substring(2);
					if (s.endsWith("L")) {
						try {
							s = s.substring(0, s.length() - 1);
							retour.addExpression(new LeekBigInteger(word, new BigInteger(s, radix)));
						} catch (NumberFormatException e) {
							addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.INVALID_NUMBER));
							retour.addExpression(new LeekBigInteger(word, BigInteger.ZERO));
						}
					} else {
						try {
							try {
								retour.addExpression(new LeekInteger(word, Long.parseLong(s, radix)));
							} catch (NumberFormatException e2) {
								if (s.contains(".")) throw e2;
								// if number is too big, try to parse it as a BigInteger
								else retour.addExpression(new LeekBigInteger(word, new BigInteger(s, radix)));
							}
						} catch (NumberFormatException e) {
							s = word.getWord().replace("_", "");
							try {
								retour.addExpression(new LeekReal(word, Double.parseDouble(s)));
							} catch (NumberFormatException e2) {
								addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.INVALID_NUMBER));
								retour.addExpression(new LeekInteger(word, 0));
							}
						}
					}
				} else if (word.getType() == TokenType.LEMNISCATE) {

					retour.addExpression(new LeekReal(word, Double.POSITIVE_INFINITY));

				} else if (word.getType() == TokenType.PI) {

					retour.addExpression(new LeekReal(word, Math.PI));

				} else if (word.getType() == TokenType.VAR_STRING) {

					retour.addExpression(new LeekString(word, word.getWord()));

				} else if (word.getType() == TokenType.BRACKET_LEFT) {

					retour.addExpression(readArrayOrMapOrInterval(mTokens.eat()));

				} else if (word.getType() == TokenType.BRACKET_RIGHT) {

					var token = mTokens.eat();
					if (mTokens.get().getType() == TokenType.DOT_DOT) {
						// interval `]..`
						mTokens.skip();
						retour.addExpression(readInterval(token, null));
					} else {
						// interval `]x..`
						var expression = readExpression(true);
						var dot_dot = mTokens.eat();
						if (dot_dot.getType() != TokenType.DOT_DOT) {
							addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.DOT_DOT_EXPECTED));
						}
						retour.addExpression(readInterval(token, expression));
					}

				} else if (word.getType() == TokenType.OPERATOR && word.getWord().equals("<")) {

					retour.addExpression(readSet(mTokens.eat()));

				} else if (getVersion() >= 2 && word.getType() == TokenType.ACCOLADE_LEFT) {

					// Déclaration d'un objet
					var token = mTokens.eat();
					var object = new LeekObject(token);

					while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.ACCOLADE_RIGHT) {
						if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
						if (mTokens.get().getType() != TokenType.STRING) {
							addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS));
						}
						String key = mTokens.get().getWord();
						mTokens.skip();

						if (!mTokens.get().getWord().equals(":")) {
							addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS));
						}
						mTokens.skip();

						var value = readExpression(true);
						object.addEntry(key, value);

						if (mTokens.get().getType() == TokenType.VIRG) {
							mTokens.skip();
						}
					}
					if (mTokens.get().getType() != TokenType.ACCOLADE_RIGHT) {
						addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.CLOSING_PARENTHESIS_EXPECTED));
					}
					object.setClosingBrace(mTokens.get());
					retour.addExpression(object);

				} else if (word.getType() == TokenType.CLASS) {
					retour.addExpression(new LeekVariable(this, word, VariableType.LOCAL));
				} else if (word.getType() == TokenType.THIS) {
					retour.addExpression(new LeekVariable(this, word, VariableType.LOCAL));
				} else if (word.getType() == TokenType.TRUE) {
					retour.addExpression(new LeekBoolean(word, true));
				} else if (word.getType() == TokenType.FALSE) {
					retour.addExpression(new LeekBoolean(word, false));
				} else if (word.getType() == TokenType.FUNCTION) {
					retour.addExpression(readAnonymousFunction());
				} else if (word.getType() == TokenType.NULL) {
					retour.addExpression(new LeekNull(word));
				} else if (getVersion() >= 2 && word.getType() == TokenType.NEW) {
					retour.addUnaryPrefix(Operators.NEW, word);
				} else if (word.getType() == TokenType.NOT) {
					retour.addUnaryPrefix(Operators.NOT, word);
				} else if (word.getType() == TokenType.SUPER) {
					// super doit être dans une méthode
					if (mCurrentClass == null) {
						addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.KEYWORD_MUST_BE_IN_CLASS));
						retour.addExpression(new LeekVariable(this, word, VariableType.LOCAL));
					} else {
						if (mCurrentClass.getParentToken() == null) {
							addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.SUPER_NOT_AVAILABLE_PARENT));
						}
						retour.addExpression(new LeekVariable(word, VariableType.SUPER, Type.CLASS, mCurrentClass));
					}
				} else if (word.getType() == TokenType.STRING) {

					if (mMain.hasGlobal(word.getWord())) {
						retour.addExpression(new LeekVariable(this, word, VariableType.GLOBAL));
					} else {
						retour.addExpression(new LeekVariable(this, word, VariableType.LOCAL));
					}
				} else if (word.getType() == TokenType.PAR_LEFT) {
					var leftParenthesis = mTokens.eat(); // On avance le curseur pour bien être au début de l'expression

					var exp = readExpression();
					if (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.PAR_RIGHT) {
						throw new LeekCompilerException(mTokens.get(), Error.CLOSING_PARENTHESIS_EXPECTED);
					}
					var rightParenthesis = mTokens.get();
					retour.addExpression(new LeekParenthesis(exp, leftParenthesis, rightParenthesis));

				} else if (word.getType() == TokenType.OPERATOR) {
					// Si c'est un opérateur (il doit forcément être unaire et de type préfix (! ))
					int operator = Operators.getOperator(word.getWord(), getVersion());
					if (operator == Operators.MINUS) operator = Operators.UNARY_MINUS;
					else if (operator == Operators.DECREMENT) operator = Operators.PRE_DECREMENT;
					else if (operator == Operators.INCREMENT) operator = Operators.PRE_INCREMENT;
					else if (operator == Operators.NON_NULL_ASSERTION) operator = Operators.NOT;

					if (Operators.isUnaryPrefix(operator)) {
						// Si oui on l'ajoute
						retour.addUnaryPrefix(operator, word);
					} else {
						addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.OPERATOR_UNEXPECTED));
					}
				} else {
					addError(new AnalyzeError(word, AnalyzeErrorLevel.ERROR, Error.VALUE_EXPECTED));
				}
			}
			mTokens.skip();
		}
		// Avant de retourner, on valide l'expression
		Expression result = retour;
		if (retour.getOperator() == -1) {
			result = retour.getExpression1();
		}
		if (getVersion() == 1 && result instanceof LeekExpression) {
			var expr = (LeekExpression) result;
			if (expr.getOperator() == Operators.NOT && expr.getExpression2() == null) {
				// Un "not" tout seul est valide en LS 1.0
				result = new LeekVariable(this, expr.getOperatorToken(), VariableType.LOCAL);
			}
		}
		if (result == null) {
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.UNCOMPLETE_EXPRESSION));
			return new LeekNull(mTokens.eat());
		}
		try {
			result.validExpression(this, mMain);
		} catch (LeekExpressionException e) {
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, e.getError(), new String[] { e.getExpression() }));
			return new LeekNull(mTokens.eat());
		}
		return result;
	}

	private boolean wordEquals(Token word, String expected) {
		if (getVersion() <= 2) {
			return word.getWord().equalsIgnoreCase(expected);
		}
		return word.getWord().equals(expected);
	}

	private Expression readSet(Token openingToken) throws LeekCompilerException {
		var set = new LeekSet(openingToken);

		while (mTokens.hasMoreTokens() && (mTokens.get().getType() != TokenType.OPERATOR || !mTokens.get().getWord().equals(">"))) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

			set.addValue(readExpression(true, true));

			if (mTokens.get().getType() == TokenType.VIRG) {
				mTokens.skip();
			}
		}

		if (!mTokens.get().getWord().equals(">")) {
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.CLOSING_CHEVRON_EXPECTED));
		}

		set.setClosingToken(mTokens.get());

		return set;
	}

	private Expression readArrayOrMapOrInterval(Token openingBracket) throws LeekCompilerException {

		// Empty map `[:]`
		if (mTokens.get().getWord().equals(":")) {
			mTokens.skip();

			if (mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
				throw new LeekCompilerException(mTokens.get(), Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS);
			}

			if (version >= 4) {
				var container = new LeekMap(openingBracket);
				container.setClosingBracket(mTokens.get());
				return container;
			} else {
				var container = new LegacyLeekArray(openingBracket);
				container.mIsKeyVal = true;
				container.setClosingBracket(mTokens.get());
				return container;
			}
		}

		// Empty array `[]`
		if (mTokens.get().getType() == TokenType.BRACKET_RIGHT) {
			if (version >= 4) {
				var container = new LeekArray(openingBracket);
				container.setClosingBracket(mTokens.get());
				return container;
			} else {
				var container = new LegacyLeekArray(openingBracket);
				container.setClosingBracket(mTokens.get());
				return container;
			}
		}

		// Empty interval [..]
		if (mTokens.get().getType() == TokenType.DOT_DOT) {
			mTokens.skip();
			return readInterval(openingBracket, null);
		}

		var firstExpression = readExpression(true);
		if (mTokens.get().getWord().equals(":")) {
			mTokens.skip();
			if (version >= 4) {
				return readMap(openingBracket, firstExpression);
			} else {
				return readLegacyArray(openingBracket, firstExpression, true);
			}
		} else if (mTokens.get().getType() == TokenType.DOT_DOT) {
			mTokens.skip();
			return readInterval(openingBracket, firstExpression);
		} else {
			if (version >= 4) {
				return readArray(openingBracket, firstExpression);
			} else {
				return readLegacyArray(openingBracket, firstExpression, false);
			}
		}
	}

	private Expression readMap(Token openingBracket, Expression firstExpression) throws LeekCompilerException {
		var container = new LeekMap(openingBracket);

		var secondExpression = readExpression(true);
		container.addValue(this, firstExpression, secondExpression);

		if (mTokens.get().getType() == TokenType.VIRG) {
			mTokens.skip();
		}

		while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
			var key = readExpression(true);

			if (!mTokens.get().getWord().equals(":")) {
				throw new LeekCompilerException(mTokens.get(), Error.SIMPLE_ARRAY);
			}
			mTokens.skip();

			var value = readExpression(true);
			container.addValue(this, key, value);

			if (mTokens.get().getType() == TokenType.VIRG) {
				mTokens.skip();
			}
		}

		container.setClosingBracket(mTokens.get());
		return container;
	}

	private Expression readArray(Token openingBracket, Expression firstExpression) throws LeekCompilerException {
		var container = new LeekArray(openingBracket);

		container.addValue(firstExpression);

		if (mTokens.get().getType() == TokenType.VIRG) {
			mTokens.skip();
		}

		while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
			var value = readExpression(true);
			container.addValue(value);

			if (mTokens.get().getWord().equals(":")) {
				throw new LeekCompilerException(mTokens.get(), Error.ASSOCIATIVE_ARRAY);
			}

			if (mTokens.get().getType() == TokenType.VIRG) {
				mTokens.skip();
			}
		}

		container.setClosingBracket(mTokens.get());
		return container;
	}

	private Expression readLegacyArray(Token openingBracket, Expression firstExpression, boolean isKeyVal) throws LeekCompilerException {

		var container = new LegacyLeekArray(openingBracket);

		// Empty array `[:]`
		if (mTokens.get().getWord().equals(":")) {
			container.mIsKeyVal = true;
			container.type = Type.MAP;
			mTokens.skip();

			if (mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
				throw new LeekCompilerException(mTokens.get(), Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS);
			}
			container.setClosingBracket(mTokens.get());
			return container;
		}

		// Empty array `[]`
		if (mTokens.get().getType() == TokenType.BRACKET_RIGHT) {
			container.addValue(firstExpression);
			container.setClosingBracket(mTokens.get());
			return container;
		}

		if (isKeyVal) {
			var secondExpression = readExpression(true);
			container.addValue(this, firstExpression, secondExpression);
		} else {
			container.addValue(firstExpression);
		}

		if (mTokens.get().getType() == TokenType.VIRG) {
			mTokens.skip();
		}

		while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.BRACKET_RIGHT) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
			if (isKeyVal) {
				var key = readExpression(true);
				if (!mTokens.get().getWord().equals(":")) {
					throw new LeekCompilerException(mTokens.get(), Error.SIMPLE_ARRAY);
				}
				mTokens.skip();

				var value = readExpression(true);
				container.addValue(this, key, value);
			} else {
				var value = readExpression(true);
				container.addValue(value);

				if (mTokens.get().getWord().equals(":")) {
					throw new LeekCompilerException(mTokens.get(), Error.ASSOCIATIVE_ARRAY);
				}
			}

			if (mTokens.get().getType() == TokenType.VIRG) {
				mTokens.skip();
			}
		}

		container.setClosingBracket(mTokens.get());
		return container;
	}

	private Expression readInterval(Token openingBracket, Expression fromExpression) throws LeekCompilerException {

		if (mTokens.get().getType() == TokenType.BRACKET_RIGHT || mTokens.get().getType() == TokenType.BRACKET_LEFT) {
			return new LeekInterval(openingBracket, fromExpression, null, mTokens.get());
		}

		// Although an interval is not comma separated, we still parse the second
		// expression as if we did. This is in order to be more consitent as the first
		// expression is parsed as if it was comma separated
		var toExpression = readExpression(true, false, true);

		var nextToken = mTokens.get();
		if (nextToken.getWord().equals(":")) {
			throw new LeekCompilerException(mTokens.get(), Error.ASSOCIATIVE_ARRAY);
		} else if (nextToken.getType() == TokenType.VIRG) {
			throw new LeekCompilerException(mTokens.get(), Error.SIMPLE_ARRAY);
		} else if (nextToken.getType() != TokenType.BRACKET_RIGHT && nextToken.getType() != TokenType.BRACKET_LEFT) {
			throw new LeekCompilerException(mTokens.get(), Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS);
		}

		return new LeekInterval(openingBracket, fromExpression, toExpression, mTokens.get());
	}

	private LeekAnonymousFunction readAnonymousFunction() throws LeekCompilerException {
		var token = mTokens.eat();
		if (mTokens.get().getType() != TokenType.PAR_LEFT) {
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_FUNCTION));
		}
		mTokens.skip(); // Left parenthesis

		// On enregistre les block actuels
		AbstractLeekBlock initialBlock = mCurentBlock;
		var previousFunction = mCurrentFunction;
		int initialLine = mLine;
		AIFile initialAI = mAI;
		AnonymousFunctionBlock block = new AnonymousFunctionBlock(mCurentBlock, mMain, token);
		// if (initialBlock.getDeclaringVariable() != null)
		// 	block.addVariable(new LeekVariable(initialBlock.getDeclaringVariable(), VariableType.LOCAL));
		mCurentBlock = block;
		setCurrentFunction(block);

		// Lecture des paramètres
		while (mTokens.hasMoreTokens() && mTokens.get().getType() != TokenType.PAR_RIGHT) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);
			boolean is_reference = false;
			if (mTokens.get().getType() == TokenType.OPERATOR && mTokens.get().getWord().equals("@")) {
				is_reference = true;
				if (getVersion() >= 2) {
					addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.WARNING, Error.REFERENCE_DEPRECATED));
				}
				mTokens.skip();
			}
			if (mTokens.get().getType() != TokenType.STRING) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARAMETER_NAME_EXPECTED));
			}

			var type = eatType(false, false);
			var parameter = mTokens.get();
			mTokens.skip();

			if (mTokens.get().getType() == TokenType.VIRG) {
				mTokens.skip();
			}
			// else if (mTokens.get().getType() != TokenType.PAR_RIGHT) {
			// 	type = parseType(parameter.getWord());
			// 	parameter = mTokens.get();
			// 	mTokens.skip();
			// 	if (mTokens.get().getType() == TokenType.VIRG) {
			// 		mTokens.skip();
			// 	}
			// }

			block.addParameter(this, parameter, is_reference, type);
		}
		if (mTokens.eat().getType() != TokenType.PAR_RIGHT) {
			addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.PARENTHESIS_EXPECTED_AFTER_PARAMETERS));
		}

		// Type de retour
		if (mTokens.get().getType() == TokenType.ARROW) {
			mTokens.skip();

			var returnType = eatType(false, true);
			if (returnType == null) {
				addError(new AnalyzeError(mTokens.get(), AnalyzeErrorLevel.ERROR, Error.TYPE_EXPECTED));
			} else {
				block.setReturnType(returnType.getType());
			}
		}

		// Ouverture des accolades
		if (mTokens.eat().getType() != TokenType.ACCOLADE_LEFT) {
			throw new LeekCompilerException(mTokens.get(), Error.OPENING_CURLY_BRACKET_EXPECTED);
		}

		// Lecture du corp de la fonction
		while (mTokens.hasMoreTokens()) {
			if (isInterrupted()) throw new LeekCompilerException(mTokens.get(), Error.AI_TIMEOUT);

			// Fermeture des blocs ouverts
			if (mCurentBlock instanceof DoWhileBlock && !((DoWhileBlock) mCurentBlock).hasAccolade() && mCurentBlock.isFull()) {
				DoWhileBlock do_block = (DoWhileBlock) mCurentBlock;
				mCurentBlock = mCurentBlock.endInstruction();
				dowhileendBlock(do_block);
				mTokens.skip();
			} else mCurentBlock = mCurentBlock.endInstruction();
			if (!mTokens.hasMoreTokens()) break;

			// On regarde si on veut fermer la fonction anonyme
			if (mTokens.get().getType() == TokenType.ACCOLADE_RIGHT && mCurentBlock == block) {
				block.setEndToken(mTokens.get());
				break;// Fermeture de la fonction anonyme
			} else {
				compileWord();
			}
		}

		// Ajout de la fonction
		mMain.addAnonymousFunction(block);

		// On remet le bloc initial
		mCurentBlock = initialBlock;
		mLine = initialLine;
		mAI = initialAI;
		setCurrentFunction(previousFunction);

		return new LeekAnonymousFunction(block, token);
	}

	public boolean isKeyword(Token word) {
		for (var w : LexicalParser.reservedWords) {
			if (wordEquals(word, w)) return true;
		}
		return false;
	}

	public boolean isAvailable(Token word, boolean allFunctions) {
		if (getVersion() >= 3 && isKeyword(word)) return false;
		// if(LeekFunctions.isFunction(word) >= 0 || mMain.hasGlobal(word) ||
		// mMain.hasUserFunction(word, allFunctions) ||
		// mCurentBlock.hasVariable(word)) return false;
		if (mMain.hasGlobal(word.getWord()) || mMain.hasUserFunction(word.getWord(), allFunctions) || mCurentBlock.hasVariable(word.getWord())) return false;
		return true;
	}

	public boolean isGlobalAvailable(Token word) {
		if (getVersion() <= 2) {
			if (word.getWord().equalsIgnoreCase("in") || word.getWord().equalsIgnoreCase("global") || word.getWord().equalsIgnoreCase("var") || word.getWord().equalsIgnoreCase("for") || word.getWord().equalsIgnoreCase("else") || word.getWord().equalsIgnoreCase("if") || word.getWord().equalsIgnoreCase("break") || word.getWord().equalsIgnoreCase("return") || word.getWord().equalsIgnoreCase("do") || word.getWord().equalsIgnoreCase("while") || word.getWord().equalsIgnoreCase("function") || word.getWord().equalsIgnoreCase("true") || word.getWord().equalsIgnoreCase("false") || word.getWord().equalsIgnoreCase("null")) return false;
		}
		if (getVersion() >= 3 && isKeyword(word)) return false;
		// if(LeekFunctions.isFunction(word) >= 0 || mMain.hasUserFunction(word,
		// false) || mCurentBlock.hasVariable(word)) return false;
		if (mMain.hasUserFunction(word.getWord(), false) || mCurentBlock.hasVariable(word.getWord())) return false;
		return true;
	}

	public String getString() {
		return mMain.getCode();
	}

	public AbstractLeekBlock getCurrentBlock() {
		return mCurentBlock;
	}

	public AbstractLeekBlock getCurrentFunction() {
		return mCurrentFunction;
	}

	public void addError(AnalyzeError analyzeError) throws LeekCompilerException {
		this.mAI.getErrors().add(analyzeError);
		if (this.mAI.getErrors().size() > 10000) {
			throw new LeekCompilerException(mTokens.getEndOfFileToken(), Error.TOO_MUCH_ERRORS);
		}
	}

	public void setCurrentBlock(AbstractLeekBlock block) {
		mCurentBlock = block;
	}

	public LexicalParserTokenStream getTokenStream() {
		return mTokens;
	}

	public MainLeekBlock getMainBlock() {
		return mMain;
	}

	public int getVersion() {
		return this.version;
	}

	public ClassDeclarationInstruction getCurrentClass() {
		return mCurrentClass;
	}

	public void setCurrentClass(ClassDeclarationInstruction clazz) {
		this.mCurrentClass = clazz;
	}

	public void setCurrentFunction(AbstractLeekBlock block) {
		// System.out.println("setCurrentFunction " + block);
		this.mCurrentFunction = block;
	}

	public AIFile getAI() {
		return mAI;
	}

	public void setMainBlock(MainLeekBlock main) {
		this.mMain = main;
		mCurentBlock = main;
		mCurrentFunction = main;
	}

	public boolean isInConstructor() {
		if (mCurrentClass != null) {
			return mCurentBlock.isInConstructor();
		}
		return false;
	}

	public String getCurrentClassVariable() {
		if (mCurrentClass != null) {
			return "u_" + mCurrentClass.getName();
		}
		return null;
	}

	public Options getOptions() {
		return options;
	}
}
