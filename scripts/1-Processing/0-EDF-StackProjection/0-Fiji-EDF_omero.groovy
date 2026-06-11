#@String(label="Username") USERNAME
#@String(label="Password", style='password', persist=true) PASSWORD
#@String(label="Object to process", choices={"image","dataset","project","well","plate","screen"}) object_type
#@Long(label="Object ID", value=119273) id
#@Boolean(label="Show images") showImages

#@ File (style="directory") output_dir
#@ Integer sigma
#@ CommandService cs

IJ.run("Close All", "");

/* = CODE DESCRIPTION =
 * This is a template to interact with OMERO. 
 * User can specify the ID of an "image","dataset","project","well","plate","screen"
 * The selected object is then imported in FIJI
 * 
 * == INPUTS ==
 *  - host
 *  - port
 *  - credentials 
 *  - id
 *  - object type
 *  - display imported image or not
 * 
 * == OUTPUTS ==
 *  - open the image defined by id (or all images one after another from the dataset/project/... defined by id)
 * 
 * = DEPENDENCIES =
 *  - Fiji update site OMERO 5.5-5.6
 *  - simple-omero-client-5.9.2 or later : https://github.com/GReD-Clermont/simple-omero-client
 * 
 * = INSTALLATION = 
 *  Open Script and Run
 * 
 * = AUTHOR INFORMATION =
 * Code written by romain guiet & Rémy Dornier, EPFL - SV -PTECH - BIOP 
 * 04.07.2022
 * 
 * = COPYRIGHT =
 * © All rights reserved. ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP), 2022
 * 
 * Licensed under the BSD-3-Clause License:
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided 
 * that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer 
 *    in the documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products 
 *     derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, 
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; 
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF 
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * == HISTORY ==
 * - 2023.06.19 : Remove unnecessary imports
 */

/**
 * Main. Connect to OMERO, process images and disconnect from OMERO
 * 
 */

// Connection to server
host = "omero-server.epfl.ch"
port = 4064

Client user_client = new Client()
user_client.connect(host, port, USERNAME, PASSWORD.toCharArray())


if (user_client.isConnected()){
	println "\nConnected to "+host
	
	try{
		
		switch (object_type){
			case "image":	
				processImage(user_client, user_client.getImage(id))
				break	
			case "dataset":
				processDataset(user_client, user_client.getDataset(id))
				break
			case "project":
				processProject(user_client, user_client.getProject(id))
				break
			case "well":
				processWell(user_client, user_client.getWells(id))
				break
			case "plate":
				processPlate(user_client, user_client.getPlates(id))
				break
			case "screen":
				processScreen(user_client, user_client.getScreens(id))
				break
		}
		println "Importation in FIJI of "+object_type+", id "+id+": DONE !"
		
	} finally{
		user_client.disconnect()
		println "Disonnected from "+host
	}
		
}else{
	println "Not able to connect to "+host
}


/**
 * Display image metadata and import the image in Fiji
 * 
 * inputs
 * 		user_client : OMERO client
 * 		image_wpr : OMERO image
 * 
 * */
def processImage(user_client, image_wpr){
	// clear Fiji env
	IJ.run("Close All", "");
	
	// Print image information
	println "\n Image infos"
	println ("Image_name : "+image_wpr.getName() + " / id : "+ image_wpr.getId())
	def dataset_wpr_list = image_wpr.getDatasets(user_client)
	dataset_id = dataset_wpr_list[0].getId()

	
	// if the image is part of a dataset
	if(!dataset_wpr_list.isEmpty()){
		dataset_wpr_list.each{println("dataset_name : "+it.getName()+" / id : "+it.getId())};
		image_wpr.getProjects(user_client).each{println("Project_name : "+it.getName()+" / id : "+it.getId())};
	}else {// if the image is part of a plate
		def well_wpr = image_wpr.getWells(user_client).get(0)
		println ("Well_name : "+well_wpr.getName() +" / id : "+ well_wpr.getId())
		
		def plate_wpr = image_wpr.getPlates(user_client).get(0)
		println ("plate_name : "+plate_wpr.getName() + " / id : "+ plate_wpr.getId())

		def screen_wpr = image_wpr.getScreens(user_client).get(0)
		println ("screen_name : "+screen_wpr.getName() + " / id : "+ screen_wpr.getId())
	}
	
	// Show the imported image
	ImagePlus imp = image_wpr.toImagePlus(user_client);
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
	
	omero_input_path = new File( output_dir.toString() , output_name+".ome.tiff" )
	image_omeroID = user_client.getDataset(dataset_id).importImage(user_client, omero_input_path.toString())
	omero_input_path.delete()
	// TODO ? : save as jpeg for yolo processing
	//jpeg_path = new File( output_dir.toString() , output_name+".jpg" )
	//IJ.saveAs(output_imp, "Jpeg", jpeg_path.toString());
	
	// TODO : start yolo processign from fiji ? 
	
	println "DONE"
}


import ij.IJ
import net.haesleinhuepf.clij2.CLIJ2;
import ch.epfl.biop.kheops.command.KheopsExportImagePlusCommand


/**
 * Import all images from a dataset in Fiji
 * 
 * inputs
 * 		user_client : OMERO client
 * 		dataset_wpr : OMERO dataset
 * 
 * */
def processDataset( user_client, dataset_wpr ){
	dataset_wpr.getImages(user_client).each{ img_wpr ->
		processImage(user_client , img_wpr)
	}
}


/**
 * Import all images from a dataset in Fiji
 * 
 * inputs
 * 		user_client : OMERO client
 * 		dataset_wpr : OMERO dataset
 * 
 * */
def processProject( user_client, project_wpr ){
	project_wpr.getDatasets().each{ dataset_wpr ->
		processDataset(user_client , dataset_wpr)
	}
}


/**
 * Import all images from a well in Fiji
 * 
 * inputs
 * 		user_client : OMERO client
 * 		well_wpr_list : OMERO wells
 * 
 * */
def processWell(user_client, well_wpr_list){		
	well_wpr_list.each{ well_wpr ->				
		well_wpr.getWellSamples().each{			
			processImage(user_client, it.getImage())		
		}
	}	
}


/**
 * Import all images from a plate in Fiji
 * 
 * inputs
 * 		user_client : OMERO client
 * 		plate_wpr_list : OMERO plates
 * 
 * */
def processPlate(user_client, plate_wpr_list){
	plate_wpr_list.each{ plate_wpr ->	
		processWell(user_client, plate_wpr.getWells(user_client))
	} 
}


/**
 * Import all images from a screen in Fiji
 * 
 * inputs
 * 		user_client : OMERO client
 * 		screen_wpr_List : OMERO screens
 * 
 * */
def processScreen(user_client, screen_wpr_list){
	screen_wpr_list.each{ screen_wpr ->	
		processPlate(user_client, screen_wpr.getPlates())
	} 
}



/*
 * imports  
 */
import fr.igred.omero.*
import ij.*