// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package main

import "C"
import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/ioutil"
	"math/rand"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/shopspring/decimal"
	"gorm.io/datatypes"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
	"gorm.io/gorm/logger"
)

// TODO(developer): Change this to match your PGAdapter instance and database name
var connectionString = "host=/tmp port=5433 database=gorm-sample2"

// BaseModel is embedded in all other models to add common database fields.
type BaseModel struct {
	// ID is the primary key of each model. The ID is generated client side as a UUID.
	// Adding the `primaryKey` annotation is redundant for most models, as gorm will assume that the column with name ID
	// is the primary key. This is however not redundant for models that add additional primary key columns, such as
	// child tables in interleaved table hierarchies, as a missing primary key annotation here would then cause the
	// primary key column defined on the child table to be the only primary key column.
	ID string `gorm:"primaryKey;autoIncrement:false"`
	// CreatedAt and UpdatedAt are managed automatically by gorm.
	CreatedAt time.Time
	UpdatedAt time.Time
}

type Singer struct {
	BaseModel
	FirstName sql.NullString
	LastName  string
	// FullName is generated by the database. The '->' marks this a read-only field. Preferably this field should also
	// include a `default:(-)` annotation, as that would make gorm read the value back using a RETURNING clause. That is
	// however currently not supported.
	FullName string `gorm:"->;type:GENERATED ALWAYS AS (coalesce(concat(first_name,' '::varchar,last_name))) STORED;default:(-);"`
	Active   bool
	Albums   []Album
}

type Album struct {
	BaseModel
	Title           string
	MarketingBudget decimal.NullDecimal
	ReleaseDate     datatypes.Date
	CoverPicture    []byte
	SingerId        string
	Singer          Singer
	Tracks          []Track `gorm:"foreignKey:ID"`
}

// Track is interleaved in Album. The ID column is both the first part of the primary key of Track, and a
// reference to the Album that owns the Track.
type Track struct {
	BaseModel
	TrackNumber int64 `gorm:"primaryKey;autoIncrement:false"`
	Title       string
	SampleRate  float64
	Album       Album `gorm:"foreignKey:ID"`
}

type Venue struct {
	BaseModel
	Name        string
	Description string
}

type Concert struct {
	BaseModel
	Name      string
	Venue     Venue
	VenueId   string
	Singer    Singer
	SingerId  string
	StartTime time.Time
	EndTime   time.Time
}

var rnd = rand.New(rand.NewSource(time.Now().UnixNano()))

func main() {
	if err := RunSample(connectionString); err != nil {
		os.Exit(1)
	}
}

func RunSample(connString string) error {
	db, err := gorm.Open(postgres.Open(connString), &gorm.Config{
		// DisableNestedTransaction will turn off the use of Savepoints if gorm
		// detects a nested transaction. Cloud Spanner does not support Savepoints,
		// so it is recommended to set this configuration option to true.
		DisableNestedTransaction: true,
		Logger:                   logger.Default.LogMode(logger.Error),
	})
	if err != nil {
		fmt.Printf("Failed to open gorm connection: %v\n", err)
	}

	// Create the sample tables if they do not yet exist.
	if err := CreateTablesIfNotExist(db); err != nil {
		return err
	}

	fmt.Println("Starting sample...")

	// Delete all existing data to start with a clean database.
	if err := DeleteAllData(db); err != nil {
		return err
	}
	fmt.Print("Purged all existing test data\n\n")

	// Create some random Singers, Albums and Tracks.
	if err := CreateRandomSingersAndAlbums(db); err != nil {
		return err
	}
	// Print the generated Singers, Albums and Tracks.
	if err := PrintSingersAlbumsAndTracks(db); err != nil {
		return err
	}

	// Create a Concert for a random singer.
	if err := CreateVenueAndConcertInTransaction(db); err != nil {
		return err
	}
	// Print all Concerts in the database.
	if err := PrintConcerts(db); err != nil {
		return err
	}
	// Print all Albums that were released before 1900.
	if err := PrintAlbumsReleaseBefore1900(db); err != nil {
		return err
	}
	// Print all Singers ordered by last name.
	// The function executes multiple queries to fetch a batch of singers per query.
	if err := PrintSingersWithLimitAndOffset(db); err != nil {
		return err
	}
	// Print all Albums that have a title where the first character of the title matches
	// either the first character of the first name or first character of the last name
	// of the Singer.
	if err := PrintAlbumsFirstCharTitleAndFirstOrLastNameEqual(db); err != nil {
		return err
	}
	// Print all Albums whose title start with 'e'. The function uses a named argument for the query.
	if err := SearchAlbumsUsingNamedArgument(db, "e%"); err != nil {
		return err
	}

	// Update Venue description.
	if err := UpdateVenueDescription(db); err != nil {
		return err
	}
	// Use FirstOrInit to create or update a Venue.
	if err := FirstOrInitVenue(db, "Berlin Arena"); err != nil {
		return err
	}
	// Use FirstOrCreate to create a Venue if it does not already exist.
	if err := FirstOrCreateVenue(db, "Paris Central"); err != nil {
		return err
	}
	// Update all Tracks by fetching them in batches and then applying an update to each record.
	if err := UpdateTracksInBatches(db); err != nil {
		return err
	}

	// Delete a random Track from the database.
	if err := DeleteRandomTrack(db); err != nil {
		return err
	}
	// Delete a random Album from the database. This will also delete any child Track records interleaved with the
	// Album.
	if err := DeleteRandomAlbum(db); err != nil {
		return err
	}

	// Try to execute a query with a 1ms timeout. This will normally fail.
	if err := QueryWithTimeout(db); err != nil {
		return err
	}

	fmt.Printf("Finished running sample\n")
	return nil
}

// CreateRandomSingersAndAlbums creates some random test records and stores these in the database.
func CreateRandomSingersAndAlbums(db *gorm.DB) error {
	fmt.Println("Creating random singers and albums")
	if err := db.Transaction(func(tx *gorm.DB) error {
		// Create between 5 and 10 random singers.
		for i := 0; i < randInt(5, 10); i++ {
			singerId, err := CreateSinger(db, randFirstName(), randLastName())
			if err != nil {
				fmt.Printf("Failed to create singer: %v\n", err)
				return err
			}
			fmt.Print(".")
			// Create between 2 and 12 random albums
			for j := 0; j < randInt(2, 12); j++ {
				_, err = CreateAlbumWithRandomTracks(db, singerId, randAlbumTitle(), randInt(1, 22))
				if err != nil {
					fmt.Printf("Failed to create album: %v\n", err)
					return err
				}
				fmt.Print(".")
			}
		}
		return nil
	}); err != nil {
		fmt.Printf("Transaction failed: %v\n", err)
		return err
	}
	fmt.Print("\n\n")
	return nil
}

// PrintSingersAlbumsAndTracks queries and prints all Singers, Albums and Tracks in the database.
func PrintSingersAlbumsAndTracks(db *gorm.DB) error {
	fmt.Println("Fetching all singers, albums and tracks")
	var singers []*Singer
	// Preload all associations of Singer.
	if err := db.Model(&Singer{}).Preload(clause.Associations).Order("last_name").Find(&singers).Error; err != nil {
		fmt.Printf("Failed to load all singers: %v\n", err)
		return err
	}
	for _, singer := range singers {
		fmt.Printf("Singer: {%v %v}\n", singer.ID, singer.FullName)
		fmt.Printf("Albums:\n")
		for _, album := range singer.Albums {
			fmt.Printf("\tAlbum: {%v %v}\n", album.ID, album.Title)
			fmt.Printf("\tTracks:\n")
			if err := db.Model(&album).Preload(clause.Associations).Find(&album).Error; err != nil {
				fmt.Printf("Failed to load album: %v\n", err)
				return err
			}
			for _, track := range album.Tracks {
				fmt.Printf("\t\tTrack: {%v %v}\n", track.TrackNumber, track.Title)
			}
		}
	}
	fmt.Println()
	return nil
}

// CreateVenueAndConcertInTransaction creates a new Venue and a Concert in a read/write transaction.
func CreateVenueAndConcertInTransaction(db *gorm.DB) error {
	if err := db.Transaction(func(tx *gorm.DB) error {
		// Load the first singer from the database.
		singer := Singer{}
		if res := tx.First(&singer); res.Error != nil {
			return res.Error
		}
		// Create and save a Venue and a Concert for this singer.
		venue := Venue{
			BaseModel:   BaseModel{ID: uuid.NewString()},
			Name:        "Avenue Park",
			Description: `{"Capacity": 5000, "Location": "New York", "Country": "US"}`,
		}
		if res := tx.Create(&venue); res.Error != nil {
			return res.Error
		}
		concert := Concert{
			BaseModel: BaseModel{ID: uuid.NewString()},
			Name:      "Avenue Park Open",
			VenueId:   venue.ID,
			SingerId:  singer.ID,
			StartTime: parseTimestamp("2023-02-01T20:00:00-05:00"),
			EndTime:   parseTimestamp("2023-02-02T02:00:00-05:00"),
		}
		if res := tx.Create(&concert); res.Error != nil {
			return res.Error
		}
		// Return nil to instruct `gorm` to commit the transaction.
		return nil
	}); err != nil {
		fmt.Printf("Failed to create a Venue and a Concert: %v\n", err)
		return err
	}
	fmt.Println("Created a concert")
	return nil
}

// PrintConcerts prints the current concerts in the database to the console.
// It will preload all its associations, so it can directly print the properties of these as well.
func PrintConcerts(db *gorm.DB) error {
	var concerts []*Concert
	if err := db.Model(&Concert{}).Preload(clause.Associations).Find(&concerts).Error; err != nil {
		fmt.Printf("Failed to load concerts: %v\n", err)
		return err
	}
	for _, concert := range concerts {
		fmt.Printf("Concert %q starting at %v will be performed by %s at %s\n",
			concert.Name, concert.StartTime, concert.Singer.FullName, concert.Venue.Name)
	}
	fmt.Println()
	return nil
}

// UpdateVenueDescription updates the description of the 'Avenue Park' Venue.
func UpdateVenueDescription(db *gorm.DB) error {
	if err := db.Transaction(func(tx *gorm.DB) error {
		venue := Venue{}
		if res := tx.Find(&venue, "name = ?", "Avenue Park"); res != nil {
			return res.Error
		}
		// Update the description of the Venue.
		venue.Description = `{"Capacity": 10000, "Location": "New York", "Country": "US", "Type": "Park"}`

		if res := tx.Update("description", &venue); res.Error != nil {
			return res.Error
		}
		// Return nil to instruct `gorm` to commit the transaction.
		return nil
	}); err != nil {
		fmt.Printf("Failed to update Venue 'Avenue Park': %v\n", err)
		return err
	}
	fmt.Print("Updated Venue 'Avenue Park'\n\n")
	return nil
}

// FirstOrInitVenue tries to fetch an existing Venue from the database based on the name of the venue, and if not found,
// initializes a Venue struct. This can then be used to create or update the record.
func FirstOrInitVenue(db *gorm.DB, name string) error {
	venue := Venue{}
	if err := db.Transaction(func(tx *gorm.DB) error {
		// Use FirstOrInit to search and otherwise initialize a Venue entity.
		// Note that we do not assign an ID in case the Venue was not found.
		// This makes it possible for us to determine whether we need to call Create or Save, as Cloud Spanner does not
		// support `ON CONFLICT UPDATE` clauses.
		if err := tx.FirstOrInit(&venue, Venue{Name: name}).Error; err != nil {
			return err
		}
		venue.Description = `{"Capacity": 2000, "Location": "Europe/Berlin", "Country": "DE", "Type": "Arena"}`
		// Create or update the Venue.
		if venue.ID == "" {
			return tx.Create(&venue).Error
		}
		return tx.Update("description", &venue).Error
	}); err != nil {
		fmt.Printf("Failed to create or update Venue %q: %v\n", name, err)
		return err
	}
	fmt.Printf("Created or updated Venue %q\n\n", name)
	return nil
}

// FirstOrCreateVenue tries to fetch an existing Venue from the database based on the name of the venue, and if not
// found, creates a new Venue record in the database.
func FirstOrCreateVenue(db *gorm.DB, name string) error {
	venue := Venue{}
	if err := db.Transaction(func(tx *gorm.DB) error {
		// Use FirstOrCreate to search and otherwise create a Venue record.
		// Note that we manually assign the ID using the Attrs function. This ensures that the ID is only assigned if
		// the record is not found.
		return tx.Where(Venue{Name: name}).Attrs(Venue{
			BaseModel:   BaseModel{ID: uuid.NewString()},
			Description: `{"Capacity": 5000, "Location": "Europe/Paris", "Country": "FR", "Type": "Stadium"}`,
		}).FirstOrCreate(&venue).Error
	}); err != nil {
		fmt.Printf("Failed to create Venue %q if it did not exist: %v\n", name, err)
		return err
	}
	fmt.Printf("Created Venue %q if it did not exist\n\n", name)
	return nil
}

// UpdateTracksInBatches uses FindInBatches to iterate through a selection of Tracks in batches and updates each Track
// that it found.
func UpdateTracksInBatches(db *gorm.DB) error {
	fmt.Print("Updating tracks")
	updated := 0
	if err := db.Transaction(func(tx *gorm.DB) error {
		var tracks []*Track
		return tx.Where("sample_rate > 44.1").FindInBatches(&tracks, 20, func(batchTx *gorm.DB, batch int) error {
			for _, track := range tracks {
				if track.SampleRate > 50 {
					track.SampleRate = track.SampleRate * 0.9
				} else {
					track.SampleRate = track.SampleRate * 0.95
				}
				if res := tx.Model(&track).Update("sample_rate", track.SampleRate); res.Error != nil || res.RowsAffected != int64(1) {
					if res.Error != nil {
						return res.Error
					}
					return fmt.Errorf("update of Track{%s,%d} affected %d rows", track.ID, track.TrackNumber, res.RowsAffected)
				}
				updated++
				fmt.Print(".")
			}
			return nil
		}).Error
	}); err != nil {
		fmt.Printf("\nFailed to batch fetch and update tracks: %v\n", err)
		return err
	}
	fmt.Printf("\nUpdated %d tracks\n\n", updated)
	return nil
}

func PrintAlbumsReleaseBefore1900(db *gorm.DB) error {
	fmt.Println("Searching for albums released before 1900")
	var albums []*Album
	if err := db.Where(
		"release_date < ?",
		datatypes.Date(time.Date(1900, time.January, 1, 0, 0, 0, 0, time.UTC)),
	).Order("release_date asc").Find(&albums).Error; err != nil {
		fmt.Printf("Failed to load albums: %v", err)
		return err
	}
	if len(albums) == 0 {
		fmt.Println("No albums found")
	} else {
		for _, album := range albums {
			fmt.Printf("Album %q was released at %v\n", album.Title, time.Time(album.ReleaseDate).Format("2006-01-02"))
		}
	}
	fmt.Print("\n\n")
	return nil
}

func PrintSingersWithLimitAndOffset(db *gorm.DB) error {
	fmt.Println("Printing all singers ordered by last name")
	var singers []*Singer
	limit := 5
	offset := 0
	for true {
		if err := db.Order("last_name, id").Limit(limit).Offset(offset).Find(&singers).Error; err != nil {
			fmt.Printf("Failed to load singers at offset %d: %v", offset, err)
			return err
		}
		if len(singers) == 0 {
			break
		}
		for _, singer := range singers {
			fmt.Printf("%d: %v\n", offset, singer.FullName)
			offset++
		}
	}
	fmt.Printf("Found %d singers\n\n", offset)
	return nil
}

// QueryWithTimeout will try to execute a query with a 1ms timeout.
// This will normally cause a Deadline Exceeded error to be returned.
func QueryWithTimeout(db *gorm.DB) error {
	ctx, cancel := context.WithTimeout(context.Background(), 1*time.Millisecond)
	defer cancel()

	var tracks []*Track
	if err := db.WithContext(ctx).Where("substring(title, 1, 1)='a'").Find(&tracks).Error; err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			fmt.Printf("Query failed because of a timeout. This is expected.\n\n")
			return nil
		}
		fmt.Printf("Query failed with an unexpected error: %v\n", err)
		return err
	}
	fmt.Print("Successfully queried all tracks in 1ms\n\n")
	return nil
}

func PrintAlbumsFirstCharTitleAndFirstOrLastNameEqual(db *gorm.DB) error {
	fmt.Println("Searching for albums that have a title that starts with the same character as the first or last name of the singer")
	var albums []*Album
	// Join the Singer association to use it in the Where clause.
	// Note that `gorm` will use "Singer" (including quotes) as the alias for the singers table.
	// That means that all references to "Singer" in the query must be quoted, as PostgreSQL treats
	// the alias as case-sensitive.
	if err := db.Joins("Singer").Where(
		`lower(substring(albums.title, 1, 1)) = lower(substring("Singer".first_name, 1, 1))` +
			`or lower(substring(albums.title, 1, 1)) = lower(substring("Singer".last_name, 1, 1))`,
	).Order(`"Singer".last_name, "albums".release_date asc`).Find(&albums).Error; err != nil {
		fmt.Printf("Failed to load albums: %v\n", err)
		return err
	}
	if len(albums) == 0 {
		fmt.Println("No albums found that match the criteria")
	} else {
		for _, album := range albums {
			fmt.Printf("Album %q was released by %v\n", album.Title, album.Singer.FullName)
		}
	}
	fmt.Print("\n\n")
	return nil
}

// SearchAlbumsUsingNamedArgument searches for Albums using a named argument.
func SearchAlbumsUsingNamedArgument(db *gorm.DB, title string) error {
	fmt.Printf("Searching for albums like %q\n", title)
	var albums []*Album
	if err := db.Where("title like @title", sql.Named("title", title)).Order("title").Find(&albums).Error; err != nil {
		fmt.Printf("Failed to load albums: %v\n", err)
		return err
	}
	if len(albums) == 0 {
		fmt.Println("No albums found that match the criteria")
	} else {
		for _, album := range albums {
			fmt.Printf("Album %q released at %v\n", album.Title, time.Time(album.ReleaseDate).Format("2006-01-02"))
		}
	}
	fmt.Print("\n\n")
	return nil
}

// CreateSinger creates a new Singer and stores in the database.
// Returns the ID of the Singer.
func CreateSinger(db *gorm.DB, firstName, lastName string) (string, error) {
	singer := Singer{
		BaseModel: BaseModel{ID: uuid.NewString()},
		FirstName: sql.NullString{String: firstName, Valid: true},
		LastName:  lastName,
	}
	res := db.Create(&singer)
	// FullName is automatically generated by the database and should be returned to the client by
	// the insert statement.
	if singer.FullName != firstName+" "+lastName {
		return "", fmt.Errorf("unexpected full name for singer: %v", singer.FullName)
	}
	return singer.ID, res.Error
}

// CreateAlbumWithRandomTracks creates and stores a new Album in the database.
// Also generates numTracks random tracks for the Album.
// Returns the ID of the Album.
func CreateAlbumWithRandomTracks(db *gorm.DB, singerId, albumTitle string, numTracks int) (string, error) {
	albumId := uuid.NewString()
	// We cannot include the Tracks that we want to create in the definition here, as gorm would then try to
	// use an UPSERT to save-or-update the album that we are creating. Instead, we need to create the album first,
	// and then create the tracks.
	res := db.Create(&Album{
		BaseModel:       BaseModel{ID: albumId},
		Title:           albumTitle,
		MarketingBudget: decimal.NullDecimal{Decimal: decimal.NewFromFloat(randFloat64(0, 10000000))},
		ReleaseDate:     randDate(),
		SingerId:        singerId,
		CoverPicture:    randBytes(randInt(5000, 15000)),
	})
	if res.Error != nil {
		return albumId, res.Error
	}
	tracks := make([]*Track, numTracks)
	for n := 0; n < numTracks; n++ {
		tracks[n] = &Track{BaseModel: BaseModel{ID: albumId}, TrackNumber: int64(n + 1), Title: randTrackTitle(), SampleRate: randFloat64(30.0, 60.0)}
	}

	// Note: The batch size is deliberately kept small here in order to prevent the statement from getting too big and
	// exceeding the maximum number of parameters in a prepared statement. PGAdapter can currently handle at most 50
	// parameters in a prepared statement.
	res = db.CreateInBatches(tracks, 8)
	return albumId, res.Error
}

// DeleteRandomTrack will delete a randomly chosen Track from the database.
// This function shows how to delete a record with a primary key consisting of more than one column.
func DeleteRandomTrack(db *gorm.DB) error {
	track := Track{}
	if err := db.Transaction(func(tx *gorm.DB) error {
		if err := tx.First(&track).Error; err != nil {
			return err
		}
		if track.ID == "" {
			return fmt.Errorf("no track found")
		}
		if res := tx.Delete(&track); res.Error != nil || res.RowsAffected != int64(1) {
			if res.Error != nil {
				return res.Error
			}
			return fmt.Errorf("delete affected %d rows", res.RowsAffected)
		}
		return nil
	}); err != nil {
		fmt.Printf("Failed to delete a random track: %v\n", err)
		return err
	}
	fmt.Printf("Deleted track %q (%q)\n\n", track.ID, track.Title)
	return nil
}

// DeleteRandomAlbum deletes a random Album. The Album could have one or more Tracks interleaved with it, but as the
// `INTERLEAVE IN PARENT` clause includes `ON DELETE CASCADE`, the child rows will be deleted along with the parent.
func DeleteRandomAlbum(db *gorm.DB) error {
	album := Album{}
	if err := db.Transaction(func(tx *gorm.DB) error {
		if err := tx.First(&album).Error; err != nil {
			return err
		}
		if album.ID == "" {
			return fmt.Errorf("no album found")
		}
		// Note that the number of rows affected that is returned by Cloud Spanner excludes the number of child rows
		// that was deleted along with the parent row. This means that the number of rows affected should always be 1.
		if res := tx.Delete(&album); res.Error != nil || res.RowsAffected != int64(1) {
			if res.Error != nil {
				return res.Error
			}
			return fmt.Errorf("delete affected %d rows", res.RowsAffected)
		}
		return nil
	}); err != nil {
		fmt.Printf("Failed to delete a random album: %v\n", err)
		return err
	}
	fmt.Printf("Deleted album %q (%q)\n\n", album.ID, album.Title)
	return nil
}

// CreateTablesIfNotExist creates all tables that are required for this sample if tney do not yet exist.
func CreateTablesIfNotExist(db *gorm.DB) error {
	fmt.Println("Creating tables...")
	ddl, err := ioutil.ReadFile("create_data_model.sql")
	if err != nil {
		fmt.Printf("Could not read create_data_model.sql file: %v\n", err)
		return err
	}
	ddlStatements := strings.FieldsFunc(string(ddl), func(r rune) bool {
		return r == ';'
	})
	session := db.Session(&gorm.Session{SkipDefaultTransaction: true})
	for _, statement := range ddlStatements {
		if strings.TrimSpace(statement) == "" {
			continue
		}
		if err := session.Exec(statement).Error; err != nil {
			fmt.Printf("Failed to execute statement: %v\n%q", err, statement)
			return err
		}
	}
	fmt.Println("Finished creating tables")
	return nil
}

// DeleteAllData deletes all existing records in the database.
func DeleteAllData(db *gorm.DB) error {
	if err := db.Exec("DELETE FROM concerts").Error; err != nil {
		return err
	}
	if err := db.Exec("DELETE FROM venues").Error; err != nil {
		return err
	}
	if err := db.Exec("DELETE FROM albums").Error; err != nil {
		return err
	}
	if err := db.Exec("DELETE FROM singers").Error; err != nil {
		return err
	}
	return nil
}

func randFloat64(min, max float64) float64 {
	return min + rnd.Float64()*(max-min)
}

func randInt(min, max int) int {
	return min + rnd.Int()%(max-min)
}

func randDate() datatypes.Date {
	return datatypes.Date(time.Date(randInt(1850, 2010), time.Month(randInt(1, 12)), randInt(1, 28), 0, 0, 0, 0, time.UTC))
}

func randBytes(length int) []byte {
	res := make([]byte, length)
	rnd.Read(res)
	return res
}

func randFirstName() string {
	return firstNames[randInt(0, len(firstNames))]
}

func randLastName() string {
	return lastNames[randInt(0, len(lastNames))]
}

func randAlbumTitle() string {
	return adjectives[randInt(0, len(adjectives))] + " " + nouns[randInt(0, len(nouns))]
}

func randTrackTitle() string {
	return adverbs[randInt(0, len(adverbs))] + " " + verbs[randInt(0, len(verbs))]
}

var firstNames = []string{
	"Saffron", "Eleanor", "Ann", "Salma", "Kiera", "Mariam", "Georgie", "Eden", "Carmen", "Darcie",
	"Antony", "Benjamin", "Donald", "Keaton", "Jared", "Simon", "Tanya", "Julian", "Eugene", "Laurence"}
var lastNames = []string{
	"Terry", "Ford", "Mills", "Connolly", "Newton", "Rodgers", "Austin", "Floyd", "Doherty", "Nguyen",
	"Chavez", "Crossley", "Silva", "George", "Baldwin", "Burns", "Russell", "Ramirez", "Hunter", "Fuller",
}
var adjectives = []string{
	"ultra",
	"happy",
	"emotional",
	"filthy",
	"charming",
	"alleged",
	"talented",
	"exotic",
	"lamentable",
	"lewd",
	"old-fashioned",
	"savory",
	"delicate",
	"willing",
	"habitual",
	"upset",
	"gainful",
	"nonchalant",
	"kind",
	"unruly",
}
var nouns = []string{
	"improvement",
	"control",
	"tennis",
	"gene",
	"department",
	"person",
	"awareness",
	"health",
	"development",
	"platform",
	"garbage",
	"suggestion",
	"agreement",
	"knowledge",
	"introduction",
	"recommendation",
	"driver",
	"elevator",
	"industry",
	"extent",
}
var verbs = []string{
	"instruct",
	"rescue",
	"disappear",
	"import",
	"inhibit",
	"accommodate",
	"dress",
	"describe",
	"mind",
	"strip",
	"crawl",
	"lower",
	"influence",
	"alter",
	"prove",
	"race",
	"label",
	"exhaust",
	"reach",
	"remove",
}
var adverbs = []string{
	"cautiously",
	"offensively",
	"immediately",
	"soon",
	"judgementally",
	"actually",
	"honestly",
	"slightly",
	"limply",
	"rigidly",
	"fast",
	"normally",
	"unnecessarily",
	"wildly",
	"unimpressively",
	"helplessly",
	"rightfully",
	"kiddingly",
	"early",
	"queasily",
}

func parseTimestamp(ts string) time.Time {
	t, _ := time.Parse(time.RFC3339Nano, ts)
	return t.UTC()
}

// TestRunSample is used for testing.
//export TestRunSample
func TestRunSample(connString, directory string) *C.char {
	if err := os.Chdir(directory); err != nil {
		return C.CString(fmt.Sprintf("Failed to change current directory: %v", err))
	}
	if err := RunSample(connString); err != nil {
		return C.CString(fmt.Sprintf("Failed to run sample: %v", err))
	}
	return nil
}
