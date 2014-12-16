package com.apposcopy.ella;

import java.util.*;
import java.util.jar.*;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.iface.instruction.formats.*;
import org.jf.dexlib2.Opcode;
import com.apposcopy.ella.dexlib2builder.MutableMethodImplementation;
import com.apposcopy.ella.dexlib2builder.BuilderInstruction;
import com.apposcopy.ella.dexlib2builder.instruction.*;


import com.google.common.collect.Lists;

public class Instrument
{
	static MethodReference probeMethRef;

	public static String instrument(String inputFile) throws IOException
	{
		File mergedFile = mergeEllaRuntime(inputFile);

		DexFile dexFile = DexFileFactory.loadDexFile(mergedFile, 15);
		
		probeMethRef = findProbeMethRef(dexFile);
 
        final List<ClassDef> classes = Lists.newArrayList();
 
        for (ClassDef classDef: dexFile.getClasses()) {
            List<Method> methods = Lists.newArrayList(); 
            boolean modifiedMethod = false;
			String className = classDef.getType();
			if(!className.startsWith("Lcom/apposcopy/ella/runtime/")){  
				System.out.println("processing class *"+classDef.getType());
				for (Method method: classDef.getMethods()) {
					
					String name = method.getName();
					System.out.println("processing method '"+method.getDefiningClass()+": "+method.getReturnType()+ " "+ method.getName() + " p: " +  method.getParameters() + "'");
					
					MethodImplementation implementation = method.getImplementation();
					if (implementation != null) {
						MethodTransformer tr = new MethodTransformer(method);
						MethodImplementation newImplementation = null;
						//if(!method.getName().equals("<init>"))
						newImplementation = tr.transform();
						
						if(newImplementation != null){
							modifiedMethod = true;
							methods.add(new ImmutableMethod(
															method.getDefiningClass(),
															method.getName(),
															method.getParameters(),
															method.getReturnType(),
															method.getAccessFlags(),
															method.getAnnotations(),
															newImplementation));
						} else 
							methods.add(method);
					} else
						methods.add(method);
				}
			} else if(className.equals("Lcom/apposcopy/ella/runtime/Ella;")){
				modifiedMethod = true;
				for (Method method: classDef.getMethods()) {
					String name = method.getName();
					if(name.equals("<clinit>")){
						MethodImplementation code = method.getImplementation();
						int regCount = code.getRegisterCount();
						
						//get the reference to the "private static String id" field
						Field idField = null;
						for(Field f : classDef.getStaticFields()){
							if(f.getName().equals("id")){
								idField = f;
								break;
							}
						}

						MutableMethodImplementation newCode = new MutableMethodImplementation(code, regCount+1);
						newCode.addInstruction(0, new BuilderInstruction21c(Opcode.SPUT_OBJECT, regCount, idField));

						newCode.addInstruction(0, new BuilderInstruction21c(Opcode.CONST_STRING, regCount, new ImmutableStringReference(Config.g().appId)));
						
						methods.add(new ImmutableMethod(
														method.getDefiningClass(),
														method.getName(),
														method.getParameters(),
														method.getReturnType(),
														method.getAccessFlags(),
														method.getAnnotations(),
														newCode));
					} else
						methods.add(method);
				}
			}

            if (!modifiedMethod) {
                classes.add(classDef);
            } else {
                classes.add(new ImmutableClassDef(
												  classDef.getType(),
												  classDef.getAccessFlags(),
												  classDef.getSuperclass(),
												  classDef.getInterfaces(),
												  classDef.getSourceFile(),
												  classDef.getAnnotations(),
												  classDef.getFields(),
												  methods));
            }
        }

		File outputDexFile = File.createTempFile("outputclasses", ".dex");
		String outputFile = outputDexFile.getAbsolutePath();
 
        DexFileFactory.writeDexFile(outputFile, new DexFile() {
				@Override public Set<? extends ClassDef> getClasses() {
					return new AbstractSet<ClassDef>() {
						@Override public Iterator<ClassDef> iterator() {
							return classes.iterator();
						}
 
						@Override public int size() {
							return classes.size();
						}
					};
				}
			});
		return outputFile;
	}
	
	static MethodReference findProbeMethRef(DexFile dexFile)
	{
		for (ClassDef classDef: dexFile.getClasses()) {
			if(classDef.getType().startsWith("Lcom/apposcopy/ella/runtime/Ella;")){  
				for (Method method: classDef.getMethods()) {
					String name = method.getName();
					if(name.equals("m"))
						return method;
				}
			}
		}
		return null;	
	}

	static File mergeEllaRuntime(String inputFile) throws IOException
	{
		String dxJar = Config.g().dxJar;
		if(dxJar == null)
			throw new RuntimeException("Variable dx.jar not set");
		try{
			//DexMerger.main(new String[]{mergedDex.getAbsolutePath(), inputFile, ellaRuntime});
			URLClassLoader loader = new URLClassLoader(new URL[]{new URL("file://"+dxJar)});
			Class dexMergerClass = loader.loadClass("com.android.dx.merge.DexMerger");
			java.lang.reflect.Method mainMethod = dexMergerClass.getDeclaredMethod("main", (new String[0]).getClass());

			File mergedDex = File.createTempFile("ella",".dex");
			mainMethod.invoke(null, (Object) new String[]{mergedDex.getAbsolutePath(), inputFile, Config.g().ellaRuntime});
			return mergedDex;
		} catch(ClassNotFoundException e){
			throw new Error(e);
		} catch(NoSuchMethodException e){
			throw new Error(e);
		} catch(IllegalAccessException e){
			throw new Error(e);
		} catch(InvocationTargetException e){
			throw new Error(e);
		} 
	}
}