
#@ ImagePlus imp 
#@ File (style="directory") output_dir
#@ Integer sigma
#@ Boolean(label="Show images") showImages

#@ CommandService cs

IJ.run("Close All", "");
print output_dir

return
// Show the imported image

if (showImages) imp.show()
def cal = imp.getCalibration()
image_name = imp.getTitle().split(".ome.tif")[0]

// init GPU
clij2 = CLIJ2.getInstance()
// push image to GPU
imageInput  = clij2.push(imp)
long[]  dimensions = [imageInput.getWidth(), imageInput.getHeight()]
imageOutput = clij2.create(dimensions, imageInput.getNativeType())

// extendedDepthOfFocus
clij2.extendedDepthOfFocusVarianceProjection(imageInput, imageOutput, sigma)
//net.haesleinhuepf.clij2.plugins.ExtendedDepthOfFocusSobelProjection.extendedDepthOfFocusVarianceProjection(clij2, imageInput, imageOutput, sigma)

// get the result back 
output_imp = clij2.pull(imageOutput)
output_name = "EDF_sigma-"+sigma+"_"+image_name
output_imp.setTitle(output_name)
if (showImages) output_imp.show()
IJ.resetMinAndMax(output_imp)
// clean up 
clij2.clear();

output_imp.setCalibration(cal);

//IJ.run("Kheops - Convert Image to Pyramidal OME TIFF", "output_dir="+output_dir+" compression=LZW subset_channels= subset_slices= subset_frames= compress_temp_files=false");
cs.run(KheopsExportImagePlusCommand.class , true , "image", output_imp, "output_dir", output_dir,  "compression", "LZW", "subset_channels","", "subset_slices","", "subset_frames","", "compress_temp_files",false).get()


println "DONE"



import ij.IJ
import net.haesleinhuepf.clij2.CLIJ2;
import ch.epfl.biop.kheops.command.KheopsExportImagePlusCommand
import ij.*