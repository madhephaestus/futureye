package edu.uta.futureye.application;

import java.util.HashMap;

import edu.uta.futureye.algebra.Solver;
import edu.uta.futureye.algebra.intf.Matrix;
import edu.uta.futureye.algebra.intf.Vector;
import edu.uta.futureye.core.Mesh;
import edu.uta.futureye.core.NodeType;
import edu.uta.futureye.core.Refiner;
import edu.uta.futureye.core.intf.Assembler;
import edu.uta.futureye.function.AbstractFunction;
import edu.uta.futureye.function.Variable;
import edu.uta.futureye.function.basic.FC;
import edu.uta.futureye.function.basic.FDelta;
import edu.uta.futureye.function.basic.Vector2Function;
import edu.uta.futureye.function.intf.Function;
import edu.uta.futureye.function.operator.FMath;
import edu.uta.futureye.io.MeshReader;
import edu.uta.futureye.lib.assembler.AssemblerScalar;
import edu.uta.futureye.lib.element.FELinearTriangle;
import edu.uta.futureye.lib.weakform.WeakFormLaplace2D;
import edu.uta.futureye.util.Constant;
import edu.uta.futureye.util.container.ElementList;
import edu.uta.futureye.util.container.NodeList;

public class ModelDOT {
	//Light source
	public Function delta = null;
	public Variable lightPosition = null; //light source position
	public int lightNum = -1;
	
	//Inclusion mu_a
	public Function mu_a = null;
	
	//Inclusion 1/(3*mu_s') = 1.0/30.0 ?
	public Function k = new FC(0.02);
	
	/**
	 * type=1: one inclusion
	 * type=2: two inclusion
	 * @param incX
	 * @param incY
	 * @param incR
	 * @param maxMu_a
	 * @param type
	 */
	public void setMu_a(double incX, double incY, double incR, double maxMu_a,
			int type) {
		final double fcx = incX;
		final double fcy = incY;
		final double fcr = incR;
		final double fmu_a = maxMu_a;
		final double distance = 0.8;
		if(type == 1) {
			mu_a = new AbstractFunction("x","y"){
				@Override
				public double value(Variable v) {
					double bk = 0.1;
					double dx = v.get("x")-fcx;
					double dy = v.get("y")-fcy;
					if(Math.sqrt(dx*dx+dy*dy) < fcr) {
						double r = fmu_a*Math.cos((Math.PI/2)*Math.sqrt(dx*dx+dy*dy)/fcr); 
						return r<bk?bk:r;
					}
					else
						return bk;
				}
			};
		} else if(type == 2) {
			mu_a = new AbstractFunction("x","y"){
				@Override
				public double value(Variable v) {
					double bk = 0.1;
					double dx = v.get("x")-fcx;
					double dy = v.get("y")-fcy;
					double dx1 = v.get("x")-(fcx+distance);
					double dy1 = v.get("y")-fcy;
					if(Math.sqrt(dx*dx+dy*dy) < fcr) {
						double r = fmu_a*Math.cos((Math.PI/2)*Math.sqrt(dx*dx+dy*dy)/fcr); 
						return r<bk?bk:r;
					}
					else if(Math.sqrt(dx1*dx1+dy1*dy1) < fcr) {
						double r = fmu_a*Math.cos((Math.PI/2)*Math.sqrt(dx1*dx1+dy1*dy1)/fcr); 
						return r<bk?bk:r;
					}
					else
						return bk;
				}
			};			
		}
	}
	
	public void setDelta(double x,double y) {
		this.lightPosition = new Variable();
		this.lightPosition.set("x", x);
		this.lightPosition.set("y", y);
		delta = new FDelta(this.lightPosition,0.01,2e5);
		//测试将dleta函数变得平缓
		//delta = new FDelta(this.lightSource,0.05,2e5);
	}

	/**
	 * 求解混合问题，需要提供函数diriBoundaryMark来标记Dirichlet边界类型，
	 * 其余边界为Neumann类型。
	 *   如果是纯Neumann:
	 *     diriBoundaryMark=null
	 *     diri=null
	 *   如果是纯Dirichlet:
	 *     diriBoundaryMark=null
	 *     diri!=null
	 *
	 * 注意：该方法会改变mesh的边界类型，当使用同一个mesh对象求解多个不同边界条件的问题时，
	 *      需要特别设置对应的边界类型
	 * 
	 * @param mesh
	 * @param diriBoundaryMark: the function that marks which segment on the boundary is Dirichlet boundary 
	 * @param diri: the values of Dirichlet condition
	 * @return
	 */
	public Vector solveMixedBorder(Mesh mesh, Function diriBoundaryMark, Function diri) {
		//Mark border type
		HashMap<NodeType, Function> mapNTF = new HashMap<NodeType, Function>();
		if(diriBoundaryMark == null && diri == null) {
			mapNTF.put(NodeType.Robin, null);
		} else if(diriBoundaryMark == null && diri != null) {
			mapNTF.put(NodeType.Dirichlet, null);
		} else {
			mapNTF.put(NodeType.Dirichlet, diriBoundaryMark);
			mapNTF.put(NodeType.Robin, null);
		}
		mesh.clearBorderNodeMark();
		mesh.markBorderNode(mapNTF);
		
		WeakFormLaplace2D weakForm = new WeakFormLaplace2D();
		
		//Right hand side
		weakForm.setF(this.k.M(this.delta));
		//如果光源在区域外，rhs=0 与非零没有差别
		//weakForm.setF(FC.c0);

		// *** u + u_n = 0, on \Gamma2 ***
		//   A(u, v) = ((k*u_x, v_x) + (k*u_y, v_y) ) - (k*u_n,v)_\Gamma2 + (c*u, v)
		//( u_n=-u ) =>
		//   A(u, v) = ((k*u_x, v_x) + (k*u_y, v_y) ) + (k*u,v)_\Gamma2 + (c*u, v)
		weakForm.setParam(
				this.k, 
				this.mu_a, 
				null, 
				this.k //d==k,q=0 (即：u_n + u =0)
			);
		
		//bugfix 2011-5-7两种方式结果不一样？
		//Assembler assembler = new AssemblerScalarFast(mesh, weakForm);
		Assembler assembler = new AssemblerScalar(mesh, weakForm);
		System.out.println("Begin Assemble...solveMixedBorder");
		assembler.assemble();
		Matrix stiff = assembler.getStiffnessMatrix();
		Vector load = assembler.getLoadVector();
		System.out.println(load.get(55));
		//Dirichlet condition
		if(diri != null)
			assembler.imposeDirichletCondition(diri);
		System.out.println("Assemble done!");

		Solver solver = new Solver();
		Vector u = solver.solveAuto(stiff, load);
		return u;
	}
	
	public Vector solveNitsches(Mesh mesh, Function diri, double eps) {
		//Mark border type
		HashMap<NodeType, Function> mapNTF = new HashMap<NodeType, Function>();
		mapNTF.put(NodeType.Robin, null);
		mesh.clearBorderNodeMark();
		mesh.markBorderNode(mapNTF);
		
		WeakFormLaplace2D weakForm = new WeakFormLaplace2D();
		
		//Right hand side
		weakForm.setF(this.k.M(this.delta));

		//Nitsches:  
		//    k*u_n = (k/\eps)*(u0-u)
		//=>
		//   (k/\eps)*u + k*u_n = (k/\eps)*u0
		//Robin:  d*u + k*u_n= q
		weakForm.setParam(
				this.k, this.mu_a, this.k.D(eps).M(diri), this.k.D(eps)
			);
		
		Assembler assembler = new AssemblerScalar(mesh, weakForm);
		System.out.println("Begin Assemble...solveMixedBorder");
		assembler.assemble();
		Matrix stiff = assembler.getStiffnessMatrix();
		Vector load = assembler.getLoadVector();
		System.out.println("Assemble done!");

		Solver solver = new Solver();
		Vector u = solver.solveAuto(stiff, load);
		return u;
	}
	
	public Vector solveNeumann(Mesh mesh) {
		return solveMixedBorder(mesh,null,null);
	}

	public Vector solveDirichlet(Mesh mesh, Function diri) {
		return solveMixedBorder(mesh,null,diri);
	}	
	
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		String outputFolder = "ModelDOT";
//		String gridFileBig = "prostate_test3_ex.grd";
//		String gridFileSmall = "prostate_test3.grd";
		String gridFileBig = "prostate_test7_ex.grd";
		String gridFileSmall = "prostate_test7.grd";
//		String gridFileBig = "prostate_test8_ex.grd";
		//String gridFileBig = "prostate_test8_all.grd";//整个是一个区域，不是两个拼接而成
//		String gridFileSmall = "prostate_test8.grd";

		ModelDOT model = new ModelDOT();
		model.setMu_a(2.0, 2.6, 0.5, //(x,y;r)
				0.2, //maxMu_a
				1); //type
		model.setDelta(1.5, 3.5);
	
		MeshReader readerForward = new MeshReader(gridFileBig);
		Mesh meshBig = readerForward.read2DMesh();
		MeshReader readerGCM = new MeshReader(gridFileSmall);
		Mesh meshSmall = readerGCM.read2DMesh();
		
		//Use element library to assign degree of freedom (DOF) to element
		Tools.assignLinearShapFunction(meshBig);
		Tools.assignLinearShapFunction(meshSmall);
		meshBig.computeNodeBelongsToElements();
		meshBig.computeNeighborNodes();
		meshSmall.computeNodeBelongsToElements();
		meshSmall.computeNeighborNodes();		
		
		//TEST 1.
		Vector uBig = model.solveNeumann(meshBig);
		Tools.plotVector(meshBig, outputFolder, "u_big.dat", uBig);
		
		//TEST 2.
		Vector uSmallExtract = Tools.extractData(meshBig, meshSmall, uBig);
		Tools.plotVector(meshSmall, outputFolder, "u_small_extract.dat", uSmallExtract);
		//NodeList nodes = meshSmall.getNodeList();
		//NOT necessary:
		//for(int i=1;i<=nodes.size();i++) {
		//	if(nodes.at(i).isInnerNode())
		//		uSmallForBoundary.set(i,0.0);
		//}
		Vector uSmallDiri = model.solveDirichlet(meshSmall, new Vector2Function(uSmallExtract));
		Tools.plotVector(meshSmall, outputFolder, "u_small_diri.dat", uSmallDiri);
		Tools.plotVector(meshSmall, outputFolder, "u_small_extract_diri_diff.dat", 
				FMath.axpy(-1.0, uSmallDiri, uSmallExtract));
		
		Vector a = Tools.solveParamInverse(meshSmall, uSmallDiri, 
				model.k.M(model.delta), model.k,FC.c(0.1));
		Tools.plotVector(meshSmall, outputFolder, "a_sovle.dat", a);
		
		//stableFactor=0.0求出的导数与Tecplot计算的一致
		long begin,end;
		begin = System.currentTimeMillis();
		Vector uBig_x = Tools.computeDerivative(meshBig, uBig, "x", 0.0);
		Vector uBig_y = Tools.computeDerivative(meshBig, uBig, "y", 0.0);
		end = System.currentTimeMillis();
		System.out.println("computeDerivative: Time="+(end-begin));
		Tools.plotVector(meshBig, outputFolder, "u_big_x.dat", uBig_x);
		Tools.plotVector(meshBig, outputFolder, "u_big_y.dat", uBig_y);
		
		begin = System.currentTimeMillis();
		Vector uBig_x2 = Tools.computeDerivativeFast(meshBig, uBig, "x");
		Vector uBig_y2 = Tools.computeDerivativeFast(meshBig, uBig, "y");
		end = System.currentTimeMillis();
		System.out.println("computeDerivativeFast: Time="+(end-begin));
		Tools.plotVector(meshBig, outputFolder, "u_big_x2.dat", uBig_x2);
		Tools.plotVector(meshBig, outputFolder, "u_big_y2.dat", uBig_y2);
		
		Vector aBig = Tools.solveParamInverse(meshBig, uBig, 
				model.k.M(model.delta), model.k,FC.c(0.1));
		Tools.plotVector(meshBig, outputFolder, "a_sovle_big.dat", aBig);
		
		//First Guess a(x)
		model.setMu_a(2.0, 2.6, 0.5, //(x,y;r)
				0.2, //maxMu_a
				1); //type
		Vector uBigGuess = model.solveNeumann(meshBig);
		Tools.plotVector(meshBig, outputFolder, "u_big_guess.dat", uBigGuess);
		
		Vector uSmallGuess = Tools.extractData(meshBig, meshSmall, uBigGuess);
		Tools.plotVector(meshSmall, outputFolder, "u_small_guess.dat", uSmallGuess);
		
		Vector uSmallApproximate = model.solveDirichlet(meshSmall, new Vector2Function(uSmallExtract));
		Tools.plotVector(meshSmall, outputFolder, "u_small_approximate.dat", uSmallApproximate);
		Tools.plotVector(meshSmall, outputFolder, "u_small_guess_approximate_diff.dat", 
				FMath.axpy(-1.0, uSmallGuess, uSmallApproximate));
		
		Vector aApproximate = Tools.solveParamInverse(meshSmall, uSmallApproximate, 
				model.k.M(model.delta), model.k,FC.c(0.1));
		Tools.plotVector(meshSmall, outputFolder, "a_sovle_approximate.dat", aApproximate);
		
		//Adaptive
		ElementList eToRefine = Tools.computeRefineElement(meshBig, aBig, 0.03);
		Refiner.refineOnce(meshBig, eToRefine);
		Tools.assignLinearShapFunction(meshBig);
		Vector uBigRefine = model.solveNeumann(meshBig);
		Tools.plotVector(meshBig, outputFolder, "u_big_refine.dat", uBigRefine);
		
		Vector uBigRefine_x = Tools.computeDerivativeFast(meshBig, uBigRefine, "x");
		Vector uBigRefine_y = Tools.computeDerivativeFast(meshBig, uBigRefine, "y");
		Tools.plotVector(meshBig, outputFolder, "u_big_refine_x.dat", uBigRefine_x);
		Tools.plotVector(meshBig, outputFolder, "u_big_refine_y.dat", uBigRefine_y);

		Vector aBigRefine = Tools.solveParamInverse(meshBig, uBigRefine, 
				model.k.M(model.delta), model.k,FC.c(0.1));
		Tools.plotVector(meshBig, outputFolder, "a_sovle_big_refine.dat", aBigRefine);
		
		//TEST 3. Only up side of the domain is Dirichlet boundary
		Function diriBoundaryMark = new AbstractFunction("x","y"){
			@Override
			public double value(Variable v) {
				//double x = v.get("x");
				double y = v.get("y");
				if(Math.abs(y - 3.0) < Constant.eps)
					return 1.0;
				else
					return -1.0;
			}
		};
		Vector uMix = model.solveMixedBorder(meshSmall, diriBoundaryMark, new Vector2Function(uSmallExtract));
		Tools.plotVector(meshSmall, outputFolder, "u_mix.dat", uMix);
	}

}