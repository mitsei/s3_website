package s3.website

import org.specs2.mutable.{After, Specification}
import s3.website.model._
import org.specs2.specification.Scope
import org.apache.commons.io.FileUtils
import org.apache.commons.codec.digest.DigestUtils
import java.io.File
import scala.util.Random
import org.mockito.{Matchers, ArgumentCaptor}
import org.mockito.Mockito._
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._
import s3.website.S3.S3ClientProvider
import scala.collection.JavaConversions._
import s3.website.model.NewFile
import scala.Some
import com.amazonaws.AmazonServiceException
import org.apache.commons.codec.digest.DigestUtils.md5Hex

class S3WebsiteSpec extends Specification {

  "gzip: true" should {
    "update a gzipped S3 object if the contents has changed" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFilesAndContent(
        config = defaultConfig.copy(gzip = Some(Left(true))),
        localFilesWithContent = ("styles.css", "<h1>hi again</h1>") :: Nil
      )
      setS3Files(S3File("styles.css", "1c5117e5839ad8fc00ce3c41296255a1" /* md5 of the gzip of the file contents */))
      Push.pushSite
      sentPutObjectRequest.getKey must equalTo("styles.css")
    }

    "not update a gzipped S3 object if the contents has not changed" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFilesAndContent(
        config = defaultConfig.copy(gzip = Some(Left(true))),
        localFilesWithContent = ("styles.css", "<h1>hi</h1>") :: Nil
      )
      setS3Files(S3File("styles.css", "1c5117e5839ad8fc00ce3c41296255a1" /* md5 of the gzip of the file contents */))
      Push.pushSite
      noUploadsOccurred must beTrue
    }
  }

  """
    gzip:
      - .xml
  """ should {
    "update a gzipped S3 object if the contents has changed" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFilesAndContent(
        config = defaultConfig.copy(gzip = Some(Right(".xml" :: Nil))),
        localFilesWithContent = ("file.xml", "<h1>hi again</h1>") :: Nil
      )
      setS3Files(S3File("file.xml", "1c5117e5839ad8fc00ce3c41296255a1" /* md5 of the gzip of the file contents */))
      Push.pushSite
      sentPutObjectRequest.getKey must equalTo("file.xml")
    }
  }


  "push" should {
    "not upload a file if it has not changed" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFilesAndContent(localFilesWithContent = ("index.html", "<div>hello</div>") :: Nil)
      setS3Files(S3File("index.html", md5Hex("<div>hello</div>")))
      Push.pushSite
      noUploadsOccurred must beTrue
    }

    "update a file if it has changed" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFilesAndContent(localFilesWithContent = ("index.html", "<h1>old text</h1>") :: Nil)
      setS3Files(S3File("index.html", md5Hex("<h1>new text</h1>")))
      Push.pushSite
      sentPutObjectRequest.getKey must equalTo("index.html")
    }

    "create a file if does not exist on S3" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFilesAndContent(localFilesWithContent = ("index.html", "<h1>hello</h1>") :: Nil)
      Push.pushSite
      sentPutObjectRequest.getKey must equalTo("index.html")
    }

    "delete files that are on S3 but not on local file system" in new SiteDirectory with MockS3 {
      implicit val site = buildSite()
      setS3Files(S3File("old.html", md5Hex("<h1>old text</h1>")))
      Push.pushSite
      sentDelete must equalTo("old.html")
    }
  }

  "push exit status" should {
    "be 0 all uploads succeed" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFilesAndContent(localFilesWithContent = ("index.html", "<h1>hello</h1>") :: Nil)
      Push.pushSite must equalTo(0)
    }

    "be 1 if any of the uploads fails" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFilesAndContent(localFilesWithContent = ("index.html", "<h1>hello</h1>") :: Nil)
      when(amazonS3Client.putObject(Matchers.any(classOf[PutObjectRequest]))).thenThrow(new AmazonServiceException("AWS failed"))
      Push.pushSite must equalTo(1)
    }
  }

  "exclude_from_upload: string" should {
    "result in matching files not being uploaded" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(
        config = defaultConfig.copy(exclude_from_upload = Some(Left(".DS_.*?"))),
        localFiles = ".DS_Store" :: Nil
      )
      Push.pushSite
      noUploadsOccurred must beTrue
    }
  }

  "s3_website.yml file" should {
    "never be uploaded" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(localFiles = "s3_website.yml" :: Nil)
      Push.pushSite
      noUploadsOccurred must beTrue
    }
  }

  """
     exclude_from_upload:
       - regex
       - another_exclusion
  """ should {
    "result in matching files not being uploaded" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(
        config = defaultConfig.copy(exclude_from_upload = Some(Right(".DS_.*?" :: "logs" :: Nil))),
        localFiles = ".DS_Store" :: "logs/test.log" :: Nil
      )
      Push.pushSite
      noUploadsOccurred must beTrue
    }
  }

  "max-age in config" can {
    "be applied to all files" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(defaultConfig.copy(max_age = Some(Left(60))), localFiles = "index.html" :: Nil)
      Push.pushSite
      sentPutObjectRequest.getMetadata.getCacheControl must equalTo("max-age=60")
    }

    "be applied to files that match the glob" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(defaultConfig.copy(max_age = Some(Right(Map("*.html" -> 90)))), localFiles = "index.html" :: Nil)
      Push.pushSite
      sentPutObjectRequest.getMetadata.getCacheControl must equalTo("max-age=90")
    }

    "be applied to directories that match the glob" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(defaultConfig.copy(max_age = Some(Right(Map("assets/**/*.js" -> 90)))), localFiles = "assets/lib/jquery.js" :: Nil)
      Push.pushSite
      sentPutObjectRequest.getMetadata.getCacheControl must equalTo("max-age=90")
    }

    "not be applied if the glob doesn't match" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(defaultConfig.copy(max_age = Some(Right(Map("*.js" -> 90)))), localFiles = "index.html" :: Nil)
      Push.pushSite
      sentPutObjectRequest.getMetadata.getCacheControl must beNull
    }
  }

  "s3_reduced_redundancy: true in config" should {
    "result in uploads being marked with reduced redundancy" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(defaultConfig.copy(s3_reduced_redundancy = Some(true)), localFiles = "index.html" :: Nil)
      Push.pushSite
      sentPutObjectRequest.getStorageClass must equalTo("REDUCED_REDUNDANCY")
    }
  }

  "s3_reduced_redundancy: false in config" should {
    "result in uploads being marked with the default storage class" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(defaultConfig.copy(s3_reduced_redundancy = Some(false)), localFiles = "index.html" :: Nil)
      Push.pushSite
      sentPutObjectRequest.getStorageClass must beNull
    }
  }

  "redirect in config" should {
    "result in a redirect instruction that is sent to AWS" in new SiteDirectory with MockS3 {
      implicit val site = buildSite(defaultConfig.copy(redirects = Some(Map("index.php" -> "/index.html"))))
      Push.pushSite
      sentPutObjectRequest.getRedirectLocation must equalTo("/index.html")
    }
  }

  "redirect in config and an object on the S3 bucket" should {
    "not result in the S3 object being deleted" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(
        localFiles = "index.html" :: Nil,
        config = defaultConfig.copy(redirects = Some(Map("index.php" -> "/index.html")))
      )
      setS3Files(S3File("index.php", "md5"))
      Push.pushSite
      noDeletesOccurred must beTrue
    }
  }

  "dotfiles" should {
    "be included in the pushed files" in new SiteDirectory with MockS3 {
      implicit val site = siteWithFiles(localFiles = ".vimrc" :: Nil)
      Push.pushSite
      sentPutObjectRequest.getKey must equalTo(".vimrc")
    }
  }

  trait MockS3 extends Scope {
    val amazonS3Client = mock(classOf[AmazonS3])
    implicit val s3ClientProvider: S3ClientProvider = _ => amazonS3Client
    val s3ObjectListing = new ObjectListing
    when(amazonS3Client.listObjects(Matchers.any(classOf[ListObjectsRequest]))).thenReturn(s3ObjectListing)

    def setS3Files(s3Files: S3File*) {
      s3Files.foreach { s3File =>
        s3ObjectListing.getObjectSummaries.add({
          val summary = new S3ObjectSummary
          summary.setETag(s3File.md5)
          summary.setKey(s3File.s3Key)
          summary
        })
      }
    }

    val s3 = new S3()

    def asSeenByS3Client(upload: Upload)(implicit config: Config): PutObjectRequest = {
      Await.ready(s3.upload(upload withUploadType NewFile), Duration("1 s"))
      val req = ArgumentCaptor.forClass(classOf[PutObjectRequest])
      verify(amazonS3Client).putObject(req.capture())
      req.getValue
    }

    def sentPutObjectRequests: Seq[PutObjectRequest] = {
      val req = ArgumentCaptor.forClass(classOf[PutObjectRequest])
      verify(amazonS3Client).putObject(req.capture())
      req.getAllValues
    }

    def sentPutObjectRequest = sentPutObjectRequests.ensuring(_.length == 1).head

    def sentDeletes: Seq[S3Key] = {
      val deleteKey = ArgumentCaptor.forClass(classOf[S3Key])
      verify(amazonS3Client).deleteObject(Matchers.anyString(), deleteKey.capture())
      deleteKey.getAllValues
    }

    def sentDelete = sentDeletes.ensuring(_.length == 1).head

    def noDeletesOccurred = {
      verify(amazonS3Client, never()).deleteObject(Matchers.anyString(), Matchers.anyString())
      true // Mockito is based on exceptions
    }

    def noUploadsOccurred = {
      verify(amazonS3Client, never()).putObject(Matchers.any(classOf[PutObjectRequest]))
      true // Mockito is based on exceptions
    }
    
    type S3Key = String
  }
  
  trait SiteDirectory extends After {
    val siteDir = new File(FileUtils.getTempDirectory, "site" + Random.nextLong())
    siteDir.mkdir()

    def after {
      FileUtils.forceDelete(siteDir)
    }

    def buildSite(config: Config = defaultConfig): Site = Site(siteDir.getAbsolutePath, config)

    def siteWithFilesAndContent(config: Config = defaultConfig, localFilesWithContent: Seq[(String, String)]): Site = {
      localFilesWithContent.foreach {
        case (filePath, content) =>
          val file = new File(siteDir, filePath)
          FileUtils.forceMkdir(file.getParentFile)
          file.createNewFile()
          FileUtils.write(file, content)
      }
      buildSite(config)
    }

    def siteWithFiles(config: Config = defaultConfig, localFiles: Seq[String]): Site =
      siteWithFilesAndContent(config, localFilesWithContent = localFiles.map((_, "file contents")))
  }

  val defaultConfig = Config(
    s3_id = "foo",
    s3_secret = "bar",
    s3_bucket = "bucket",
    s3_endpoint = S3Endpoint.defaultEndpoint,
    max_age = None,
    gzip = None,
    gzip_zopfli = None,
    ignore_on_server = None,
    exclude_from_upload = None,
    s3_reduced_redundancy = None,
    cloudfront_distribution_id = None,
    cloudfront_invalidate_root = None,
    redirects = None,
    concurrency_level = 1
  )
}