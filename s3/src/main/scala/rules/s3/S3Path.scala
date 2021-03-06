package rules.s3

case class S3Path(bucket: String, obj: String) {
  def path: String = s"s3://$bucket/$obj"
}