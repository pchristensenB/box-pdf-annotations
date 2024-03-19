package com.box.pse.annotations;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxConfig;
import com.box.sdk.BoxDeveloperEditionAPIConnection;
import com.box.sdk.BoxFile;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class BoxPDFAnnotations {
	public static void main(String[] args) throws Exception {
		String userId = args[0];
		String fileId = args[1];
		String configFile = args[2];
		Reader reader = new FileReader(configFile);
		BoxConfig config = BoxConfig.readFrom(reader);

		final BoxAPIConnection api = BoxDeveloperEditionAPIConnection.getUserConnection(userId, config, null);
		//Get document
		BoxFile file = new BoxFile(api,fileId);
		File tempFile = File.createTempFile(UUID.randomUUID().toString(),"pdf");
		FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
		file.download(fileOutputStream);
		fileOutputStream.close();
		System.out.println(file.getInfo("file_version").getVersion().getVersionID());
		HttpResponse<JsonNode> jsonNode = Unirest.get("https://api.box.com/2.0/undoc/annotations?file_id=" + file.getID() + "&file_version_id=" + file.getInfo("file_version").getVersion().getVersionID() + "&marker=&limit=1000")
				.header("Authorization","Bearer " + api.getAccessToken())
				.asJson();

		JSONArray boxAnnotations =jsonNode.getBody().getObject().getJSONArray("entries");
		System.out.println(boxAnnotations);
		PDDocument document = PDDocument.load(tempFile);


		try {
			for (Object o : boxAnnotations) {
				//Get the annotation as JSON
				JSONObject boxAnnotation = (JSONObject)o;
				//Get the page the annotations is on and load the PDF page
				PDPage page = document.getPage(Integer.valueOf(boxAnnotation.getJSONObject("target").getJSONObject("location").get("value").toString())-1);

				JSONObject target = boxAnnotation.getJSONObject("target");
				//Get the annotations type - either region, highlight or drawing
				String type = target.getString("type");
				//Get the annotations for the PDF
				List annotations = page.getAnnotations();
				//Get page boundaries
				float pw = page.getMediaBox().getUpperRightX();
				float ph = page.getMediaBox().getUpperRightY();
				float inch = 72;

				PDBorderStyleDictionary borderThick = new PDBorderStyleDictionary();
				borderThick.setWidth(inch / 12);  // 12th inch
				//Region or highlight are similar
				if(type.equals("region") || type.equals("highlight")) {
					JSONObject shape;
					if(type.equals("region")) {
						shape = target.getJSONObject("shape");
					}
					else {
						shape = (JSONObject)target.getJSONArray("shapes").get(0);
					}
					//Create the annotation
					PDAnnotationSquareCircle aCircle = new PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE);
					aCircle.setContents(boxAnnotation.getJSONObject("description").get("message").toString());
					aCircle.setBorderStyle(borderThick);
					PDRectangle position = new PDRectangle();
					//Get the box annotations coordinates - these are from 0-100
					//where PDF are coordinates from 0 - page height and width
					float x = Float.valueOf(shape.get("x").toString());
					float y = Float.valueOf(shape.get("y").toString());
					float width =Float.valueOf(shape.get("width").toString());
					float height = Float.valueOf(shape.get("height").toString());
					//Convert the position values to make sure annotation is in correct place
					x = (pw/100)*x;
					y=(ph/100)*(100-y);
					width = (pw/100)*width;
					height = (ph/100)*height;
					position.setUpperRightX(x + width);
					position.setUpperRightY(y-height);
					position.setLowerLeftX(x);
					position.setLowerLeftY(y  );
					aCircle.setRectangle(position);
					annotations.add(aCircle);

				}

				else if(type.equals("drawing")) {
					//For drawing use teh markup annotation
					PDAnnotationMarkup freehand = new PDAnnotationMarkup();
					PDColor color = new PDColor(new float[] {0, 0, 1}, PDDeviceRGB.INSTANCE);
					PDBorderStyleDictionary thickness = new PDBorderStyleDictionary();
					thickness.setWidth((float)2);
					freehand.getCOSObject().setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK);
					freehand.setColor(color);
					freehand.setBorderStyle(thickness);
					freehand.setContents(boxAnnotation.getJSONObject("description").get("message").toString());

					//The drawing can have a number of path groups
					JSONArray path_groups = target.getJSONArray("path_groups");
					List<float[]> inkList = new ArrayList<float[]>();
					for(Object path_group:path_groups) {
						//assume array
						JSONArray paths = ((JSONObject)path_group).getJSONArray("paths");
						for(Object path:paths) {
							JSONArray points = ((JSONObject)path).getJSONArray("points");
							float[] inkPoints = new float[points.length()*2];
							COSArray verticesArray = new COSArray();

							float[] allX = new float[points.length()];
							float[] allY = new float[points.length()];

							int k = 0, l = 0;
							for(Object point:points) {
								JSONObject p = (JSONObject) point;
								allX[k] = Float.valueOf(p.get("x").toString());
								allY[k] = Float.valueOf(p.get("y").toString());
								k++;
							}

							Arrays.sort(allX);
							Arrays.sort(allY);

							float smallestX = allX[0];
							float smallestY = allY[0];
							float largestX = allX[allX.length - 1];
							float largestY = allY[allY.length - 1];
							smallestX = (pw/100)*smallestX;
							smallestY=(ph/100)*(100-smallestY);
							largestX = (pw/100)*largestX;
							largestY=(ph/100)*(100-largestY);
							PDRectangle rectangle = new PDRectangle();
							rectangle.setLowerLeftX(smallestX);
							rectangle.setLowerLeftY(smallestY);
							rectangle.setUpperRightX(largestX);
							rectangle.setUpperRightY(largestY);
							freehand.setRectangle(rectangle);
							for(Object point:points) {
								JSONObject p = (JSONObject) point;
								verticesArray.add(new COSFloat((pw/100)*Float.valueOf(p.get("x").toString())));
								verticesArray.add(new COSFloat((ph/100)*(100-Float.valueOf(p.get("y").toString()))));
							}
							COSArray verticesArrayArray = new COSArray();
							verticesArrayArray.add(verticesArray);
							System.out.println(verticesArrayArray);
							freehand.getCOSObject().setItem(COSName.INKLIST, verticesArrayArray);
						}
					}
					annotations.add(freehand);
				}
				//Construct annotations - is this still needed in this version?
				for (Object ann : annotations) {
					((PDAnnotation) ann).constructAppearances(document);
				}

			}
			//Save output document
			document.save("./test.pdf");
		}
		finally
		{
			document.close();
		}
		//Add annottions
	}
}
